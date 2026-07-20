package com.openat.product.infrastructure.storage;

import com.openat.common.exception.BusinessException;
import com.openat.config.S3StorageProperties;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "product.image.storage", havingValue = "s3")
public class S3ImageStorageAdaptor implements ImageStorageUseCase {

  private final S3Client s3Client;
  private final S3StorageProperties properties;

  @Override
  public String store(byte[] content, String originalFilename) {
    String key = ImageStorageKeys.newKey(originalFilename);
    PutObjectRequest request =
        PutObjectRequest.builder().bucket(properties.bucket()).key(key).build();

    try {
      s3Client.putObject(request, RequestBody.fromBytes(content));
    } catch (SdkException exception) {
      logFailure("store", key, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
    return key;
  }

  @Override
  public byte[] load(String key) {
    GetObjectRequest request =
        GetObjectRequest.builder().bucket(properties.bucket()).key(key).build();

    try {
      return s3Client.getObjectAsBytes(request).asByteArray();
    } catch (NoSuchKeyException exception) {
      throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
      }
      // ListBucket 미부여 IAM에서는 없는 키 조회가 403으로 온다 — 권한 오류와 구분 불가라 warn만 남기고 404 계약 유지
      if (exception.statusCode() == 403) {
        log.warn(
            "S3 image load returned 403. bucket={}, key={}, requestId={}",
            properties.bucket(),
            key,
            exception.requestId());
        throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
      }
      logFailure("load", key, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    } catch (SdkException exception) {
      logFailure("load", key, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  private void logFailure(String operation, String key, SdkException exception) {
    if (exception instanceof S3Exception s3Exception) {
      log.error(
          "S3 image operation failed. operation={}, bucket={}, key={}, status={}, requestId={}",
          operation,
          properties.bucket(),
          key,
          s3Exception.statusCode(),
          s3Exception.requestId(),
          exception);
      return;
    }
    log.error(
        "S3 image operation failed. operation={}, bucket={}, key={}",
        operation,
        properties.bucket(),
        key,
        exception);
  }
}
