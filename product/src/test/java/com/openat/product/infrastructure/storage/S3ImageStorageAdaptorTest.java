package com.openat.product.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.common.exception.BusinessException;
import com.openat.config.S3StorageProperties;
import com.openat.product.application.dto.ImagePresignInfo;
import com.openat.product.domain.error.ProductErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3 이미지 저장소")
class S3ImageStorageAdaptorTest {

  private static final String BUCKET = "test-image-bucket";
  private static final String STAGING_PREFIX = "images/staging/";
  private static final String FINAL_PREFIX = "images/final/";
  private static final Duration PRESIGN_EXPIRY = Duration.ofMinutes(10);
  private static final DataSize MAX_UPLOAD_SIZE = DataSize.ofMegabytes(5);
  private static final String KEY_ID = "550e8400-e29b-41d4-a716-446655440000";
  private static final String FINAL_KEY = KEY_ID + ".png";
  private static final String STAGING_KEY = "staging/" + FINAL_KEY;
  private static final byte[] PNG_HEAD = {
    (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
  };

  @Mock private S3Client s3Client;
  @Mock private S3Presigner s3Presigner;
  @Mock private PresignedPutObjectRequest presignedRequest;
  @Mock private PresignedGetObjectRequest presignedDownloadRequest;
  private S3ImageStorageAdaptor adaptor;

  @BeforeEach
  void setUp() {
    S3StorageProperties properties =
        new S3StorageProperties(
            BUCKET,
            STAGING_PREFIX,
            FINAL_PREFIX,
            PRESIGN_EXPIRY,
            MAX_UPLOAD_SIZE,
            null,
            null,
            null,
            null);
    adaptor = new S3ImageStorageAdaptor(s3Client, s3Presigner, properties);
  }

  @ParameterizedTest
  @CsvSource({"image/jpeg, jpg", "image/png, png", "image/webp, webp"})
  @DisplayName("허용된 이미지 타입으로 서명을 요청하면 타입에 맞는 확장자의 staging URL을 반환한다")
  void presignUpload_allowedContentType_usesCanonicalExtension(String contentType, String extension)
      throws Exception {
    // given
    String uploadUrl = "https://example.com/upload";
    Instant expiresAt = Instant.parse("2026-07-20T12:10:00Z");
    given(presignedRequest.url()).willReturn(URI.create(uploadUrl).toURL());
    given(presignedRequest.expiration()).willReturn(expiresAt);
    given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
        .willReturn(presignedRequest);

    // when
    ImagePresignInfo result = adaptor.presignUpload(contentType);

    // then
    ArgumentCaptor<PutObjectPresignRequest> requestCaptor =
        ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    then(s3Presigner).should().presignPutObject(requestCaptor.capture());
    PutObjectPresignRequest request = requestCaptor.getValue();
    assertThat(request.signatureDuration()).isEqualTo(PRESIGN_EXPIRY);
    assertThat(request.putObjectRequest().bucket()).isEqualTo(BUCKET);
    assertThat(request.putObjectRequest().key())
        .isEqualTo(ImageStorageKeys.toStagingObjectKey(result.stagingKey(), STAGING_PREFIX))
        .startsWith(STAGING_PREFIX)
        .endsWith("." + extension);
    assertThat(request.putObjectRequest().contentType()).isEqualTo(contentType);
    assertThat(result.uploadUrl()).isEqualTo(uploadUrl);
    assertThat(result.expiresAt()).isEqualTo(expiresAt);
  }

  @Test
  @DisplayName("허용되지 않은 타입이면 IMAGE_INVALID 예외를 던지고 서명하지 않는다")
  void presignUpload_unsupportedContentType_throwsImageInvalid() {
    // when & then
    assertThatThrownBy(() -> adaptor.presignUpload("text/plain"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Presigner).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("staging 객체를 승격하면 크기를 확인하고 같은 버킷의 final prefix로 복사한다")
  void promote_stagingKey_copiesToFinalAndReturnsFinalKey() {
    // given
    String stagingKey = STAGING_KEY;
    HeadObjectResponse headResponse =
        HeadObjectResponse.builder()
            .contentLength(MAX_UPLOAD_SIZE.toBytes())
            .contentType("image/png")
            .build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(headResponse);
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willReturn(headBytes(PNG_HEAD));
    given(s3Client.copyObject(any(CopyObjectRequest.class)))
        .willReturn(CopyObjectResponse.builder().build());

    // when
    String finalKey = adaptor.promote(stagingKey);

    // then
    ArgumentCaptor<HeadObjectRequest> headCaptor = ArgumentCaptor.forClass(HeadObjectRequest.class);
    then(s3Client).should().headObject(headCaptor.capture());
    assertThat(headCaptor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(headCaptor.getValue().key()).isEqualTo(STAGING_PREFIX + FINAL_KEY);

    ArgumentCaptor<CopyObjectRequest> copyCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
    then(s3Client).should().copyObject(copyCaptor.capture());
    CopyObjectRequest copyRequest = copyCaptor.getValue();
    assertThat(copyRequest.sourceBucket()).isEqualTo(BUCKET);
    assertThat(copyRequest.sourceKey()).isEqualTo(STAGING_PREFIX + FINAL_KEY);
    assertThat(copyRequest.destinationBucket()).isEqualTo(BUCKET);
    assertThat(copyRequest.destinationKey()).isEqualTo(FINAL_PREFIX + FINAL_KEY);
    assertThat(finalKey).isEqualTo(FINAL_KEY);
  }

  @Test
  @DisplayName("이미 final인 키를 승격하면 S3 호출 없이 그대로 반환한다")
  void promote_finalKey_returnsWithoutS3Call() {
    // given
    String finalKey = FINAL_KEY;

    // when
    String result = adaptor.promote(finalKey);

    // then
    assertThat(result).isEqualTo(finalKey);
    then(s3Client).shouldHaveNoInteractions();
    then(s3Presigner).shouldHaveNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a/b.png",
        "images/final/550e8400-e29b-41d4-a716-446655440000.png",
        "not-a-uuid.png"
      })
  @DisplayName("논리 key 형식이 아니면 IMAGE_INVALID 예외를 던진다")
  void promote_invalidLogicalKey_throwsImageInvalid(String key) {
    // when & then
    assertThatThrownBy(() -> adaptor.promote(key))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Client).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("staging 객체가 최대 크기를 초과하면 IMAGE_INVALID 예외를 던지고 복사하지 않는다")
  void promote_oversizedObject_throwsImageInvalid() {
    // given
    String stagingKey = STAGING_KEY;
    HeadObjectResponse headResponse =
        HeadObjectResponse.builder().contentLength(MAX_UPLOAD_SIZE.toBytes() + 1).build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(headResponse);

    // when & then
    assertThatThrownBy(() -> adaptor.promote(stagingKey))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("저장된 콘텐츠 타입이 허용 목록 밖이면 IMAGE_INVALID 예외를 던지고 복사하지 않는다")
  void promote_unsupportedStoredContentType_throwsImageInvalid() {
    // given
    HeadObjectResponse headResponse =
        HeadObjectResponse.builder().contentLength(1L).contentType("text/html").build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(headResponse);

    // when & then
    assertThatThrownBy(() -> adaptor.promote(STAGING_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("저장된 콘텐츠 타입이 키 확장자와 다르면 IMAGE_INVALID 예외를 던지고 복사하지 않는다")
  void promote_contentTypeMismatchesKeyExtension_throwsImageInvalid() {
    // given
    HeadObjectResponse headResponse =
        HeadObjectResponse.builder().contentLength(1L).contentType("image/webp").build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(headResponse);

    // when & then
    assertThatThrownBy(() -> adaptor.promote(STAGING_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("실제 바이트가 이미지 시그니처가 아니면 IMAGE_INVALID 예외를 던지고 복사하지 않는다")
  void promote_contentIsNotImage_throwsImageInvalid() {
    // given
    HeadObjectResponse headResponse =
        HeadObjectResponse.builder().contentLength(1L).contentType("image/png").build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(headResponse);
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .willReturn(headBytes("<html><body>".getBytes(StandardCharsets.UTF_8)));

    // when & then
    assertThatThrownBy(() -> adaptor.promote(STAGING_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("시그니처 검증은 객체 앞부분만 Range로 읽는다")
  void promote_signatureCheck_readsOnlyObjectHead() {
    // given
    HeadObjectResponse headResponse =
        HeadObjectResponse.builder().contentLength(1L).contentType("image/png").build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willReturn(headResponse);
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willReturn(headBytes(PNG_HEAD));
    given(s3Client.copyObject(any(CopyObjectRequest.class)))
        .willReturn(CopyObjectResponse.builder().build());

    // when
    adaptor.promote(STAGING_KEY);

    // then
    ArgumentCaptor<GetObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectRequest.class);
    then(s3Client).should().getObjectAsBytes(requestCaptor.capture());
    GetObjectRequest request = requestCaptor.getValue();
    assertThat(request.key()).isEqualTo(STAGING_PREFIX + FINAL_KEY);
    assertThat(request.range()).isEqualTo("bytes=0-11");
  }

  @Test
  @DisplayName("staging 객체가 없으면 IMAGE_NOT_FOUND 예외를 던진다")
  void promote_noSuchKey_throwsNotFound() {
    // given
    NoSuchKeyException failure = NoSuchKeyException.builder().message("missing").build();
    given(s3Client.headObject(any(HeadObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.promote(STAGING_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("staging 객체 조회가 404를 응답하면 IMAGE_NOT_FOUND 예외를 던진다")
  void promote_s3NotFound_throwsNotFound() {
    // given
    S3Exception failure = s3Exception(404, "missing");
    given(s3Client.headObject(any(HeadObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.promote(STAGING_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("ListBucket 없는 IAM의 staging 객체 조회가 403이면 IMAGE_NOT_FOUND 예외를 던진다")
  void promote_s3AccessDenied_throwsNotFound() {
    // given
    S3Exception failure = s3Exception(403, "access denied");
    given(s3Client.headObject(any(HeadObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.promote(STAGING_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
    then(s3Client).should(never()).copyObject(any(CopyObjectRequest.class));
  }

  @Test
  @DisplayName("final 키로 조회 서명을 요청하면 final prefix 객체의 presigned GET URL을 반환한다")
  void presignDownload_finalKey_returnsPresignedUrl() throws Exception {
    // given
    String downloadUrl = "https://example.com/download";
    given(presignedDownloadRequest.url()).willReturn(URI.create(downloadUrl).toURL());
    given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .willReturn(presignedDownloadRequest);

    // when
    URI result = adaptor.presignDownload(FINAL_KEY);

    // then
    ArgumentCaptor<GetObjectPresignRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    then(s3Presigner).should().presignGetObject(requestCaptor.capture());
    GetObjectPresignRequest request = requestCaptor.getValue();
    assertThat(request.signatureDuration()).isEqualTo(PRESIGN_EXPIRY);
    assertThat(request.getObjectRequest().bucket()).isEqualTo(BUCKET);
    assertThat(request.getObjectRequest().key()).isEqualTo(FINAL_PREFIX + FINAL_KEY);
    assertThat(result).isEqualTo(URI.create(downloadUrl));
    then(s3Client).shouldHaveNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "staging/550e8400-e29b-41d4-a716-446655440000.png",
        "images/final/550e8400-e29b-41d4-a716-446655440000.png",
        "not-a-uuid.png"
      })
  @DisplayName("final key 형식이 아니면 IMAGE_INVALID 예외를 던지고 서명하지 않는다")
  void presignDownload_invalidFinalKey_throwsImageInvalid(String key) {
    // when & then
    assertThatThrownBy(() -> adaptor.presignDownload(key))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID);
    then(s3Presigner).shouldHaveNoInteractions();
    then(s3Client).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("조회 서명이 실패하면 IMAGE_STORAGE_FAILED 예외를 던진다")
  void presignDownload_sdkFailure_throwsStorageFailed() {
    // given
    SdkClientException failure = SdkClientException.create("presign failed");
    given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.presignDownload(FINAL_KEY))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_STORAGE_FAILED);
  }

  private ResponseBytes<GetObjectResponse> headBytes(byte[] content) {
    return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content);
  }

  private S3Exception s3Exception(int statusCode, String message) {
    S3Exception.Builder failureBuilder = S3Exception.builder();
    failureBuilder.message(message);
    failureBuilder.statusCode(statusCode);
    return (S3Exception) failureBuilder.build();
  }
}
