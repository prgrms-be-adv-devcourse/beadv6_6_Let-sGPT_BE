package com.openat.productimport.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@ExtendWith(MockitoExtension.class)
class S3ProductImportSourceTest {

  @Mock private S3Client s3Client;

  private S3ProductImportSource source;

  @BeforeEach
  void setUp() {
    ProductImportProperties properties =
        new ProductImportProperties(
            1000,
            5 * 1024 * 1024,
            10 * 1024 * 1024,
            1,
            List.of(),
            List.of("allowed-bucket"),
            "ap-northeast-2");
    source = new S3ProductImportSource(s3Client, properties);
  }

  @Test
  void readsObjectBelowAllowedPrefix() {
    byte[] expected = {1, 2, 3};
    given(s3Client.headObject(any(HeadObjectRequest.class)))
        .willReturn(HeadObjectResponse.builder().contentLength(3L).build());
    given(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), expected));

    byte[] actual = source.read("s3://allowed-bucket/import-1000", "images/0001.jpg", 10);

    assertThat(actual).containsExactly(expected);
    ArgumentCaptor<GetObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(GetObjectRequest.class);
    org.mockito.BDDMockito.then(s3Client).should().getObjectAsBytes(requestCaptor.capture());
    assertThat(requestCaptor.getValue().bucket()).isEqualTo("allowed-bucket");
    assertThat(requestCaptor.getValue().key()).isEqualTo("import-1000/images/0001.jpg");
  }

  @Test
  void rejectsBucketOutsideAllowList() {
    assertThatThrownBy(() -> source.validateLocation("s3://other-bucket/import-1000"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.SOURCE_NOT_ALLOWED));
  }

  @Test
  void rejectsParentPathSegment() {
    assertThatThrownBy(() -> source.read("s3://allowed-bucket/import-1000", "../secret.jpg", 10))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.INVALID_SOURCE));
  }
}
