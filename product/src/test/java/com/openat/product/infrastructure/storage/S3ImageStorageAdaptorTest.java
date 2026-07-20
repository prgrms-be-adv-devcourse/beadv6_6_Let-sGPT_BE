package com.openat.product.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.openat.common.exception.BusinessException;
import com.openat.config.S3StorageProperties;
import com.openat.product.domain.error.ProductErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3 이미지 저장소")
class S3ImageStorageAdaptorTest {

  private static final String BUCKET = "test-bucket";

  @Mock private S3Client s3Client;
  private S3ImageStorageAdaptor adaptor;

  @BeforeEach
  void setUp() {
    S3StorageProperties properties = new S3StorageProperties(BUCKET);
    adaptor = new S3ImageStorageAdaptor(s3Client, properties);
  }

  @Test
  @DisplayName("이미지를 저장하면 반환 key를 그대로 객체 key로 원본 바이트를 S3에 쓴다")
  void store_validImage_putsObjectAndReturnsKey() throws Exception {
    // given
    byte[] content = "image-bytes".getBytes();
    given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .willReturn(PutObjectResponse.builder().build());

    // when
    String key = adaptor.store(content, "photo.PNG");

    // then
    ArgumentCaptor<PutObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(PutObjectRequest.class);
    ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
    then(s3Client).should().putObject(requestCaptor.capture(), bodyCaptor.capture());
    PutObjectRequest request = requestCaptor.getValue();
    assertThat(request.bucket()).isEqualTo(BUCKET);
    assertThat(request.key()).isEqualTo(key);
    assertThat(key).endsWith(".png");
    byte[] uploaded = bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes();
    assertThat(uploaded).isEqualTo(content);
  }

  @Test
  @DisplayName("S3 객체 저장이 실패하면 IMAGE_STORAGE_FAILED 예외를 던진다")
  void store_sdkFailure_throwsStorageFailed() {
    // given
    SdkClientException failure = SdkClientException.create("put failed");
    given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.store("image-bytes".getBytes(), "photo.png"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_STORAGE_FAILED);
  }

  @Test
  @DisplayName("존재하는 S3 객체를 조회하면 이미지 바이트를 반환한다")
  void load_existingKey_returnsBytes() {
    // given
    String key = "image-key.png";
    byte[] content = "image-bytes".getBytes();
    ResponseBytes<GetObjectResponse> response =
        ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content);
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willReturn(response);

    // when
    byte[] loaded = adaptor.load(key);

    // then
    ArgumentCaptor<GetObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectRequest.class);
    then(s3Client).should().getObjectAsBytes(requestCaptor.capture());
    GetObjectRequest request = requestCaptor.getValue();
    assertThat(request.bucket()).isEqualTo(BUCKET);
    assertThat(request.key()).isEqualTo(key);
    assertThat(loaded).isEqualTo(content);
  }

  @Test
  @DisplayName("NoSuchKey 응답이면 IMAGE_NOT_FOUND 예외를 던진다")
  void load_noSuchKey_throwsNotFound() {
    // given
    NoSuchKeyException failure = NoSuchKeyException.builder().message("missing").build();
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.load("missing.png"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
  }

  @Test
  @DisplayName("S3가 404를 응답하면 IMAGE_NOT_FOUND 예외를 던진다")
  void load_s3NotFound_throwsNotFound() {
    // given
    S3Exception.Builder failureBuilder = S3Exception.builder();
    failureBuilder.message("missing");
    failureBuilder.statusCode(404);
    S3Exception failure = (S3Exception) failureBuilder.build();
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.load("missing.png"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
  }

  @Test
  @DisplayName("ListBucket 없는 IAM의 없는 키 조회(403)면 IMAGE_NOT_FOUND 예외를 던진다")
  void load_s3AccessDenied_throwsNotFound() {
    // given
    S3Exception.Builder failureBuilder = S3Exception.builder();
    failureBuilder.message("access denied");
    failureBuilder.statusCode(403);
    S3Exception failure = (S3Exception) failureBuilder.build();
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.load("missing.png"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
  }

  @Test
  @DisplayName("404·403이 아닌 S3 조회 실패면 IMAGE_STORAGE_FAILED 예외를 던진다")
  void load_sdkFailure_throwsStorageFailed() {
    // given
    SdkClientException failure = SdkClientException.create("get failed");
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).willThrow(failure);

    // when & then
    assertThatThrownBy(() -> adaptor.load("image-key.png"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_STORAGE_FAILED);
  }
}
