package com.openat.product.infrastructure.storage;

import com.openat.common.exception.BusinessException;
import com.openat.config.S3StorageProperties;
import com.openat.product.application.dto.ImagePresignInfo;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3ImageStorageAdaptor implements ImageStorageUseCase {

  private static final Map<String, String> CONTENT_TYPE_EXTENSIONS =
      Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_SIGNATURE = {
    (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
  };
  private static final byte[] WEBP_CONTAINER_SIGNATURE = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_FORMAT_SIGNATURE = {'W', 'E', 'B', 'P'};
  private static final int WEBP_FORMAT_OFFSET = 8;
  private static final int SIGNATURE_PROBE_LENGTH = 12;

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final S3StorageProperties properties;

  @Override
  public ImagePresignInfo presignUpload(String contentType) {
    String extension =
        extensionOf(contentType)
            .orElseThrow(() -> new BusinessException(ProductErrorCode.IMAGE_INVALID));

    String stagingKey = ImageStorageKeys.newStagingKey(extension);
    String stagingObjectKey =
        ImageStorageKeys.toStagingObjectKey(stagingKey, properties.stagingPrefix());
    PutObjectRequest putObjectRequest =
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(stagingObjectKey)
            .contentType(contentType)
            .build();
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(properties.presignExpiry())
            .putObjectRequest(putObjectRequest)
            .build();

    try {
      PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
      return new ImagePresignInfo(
          stagingKey, presignedRequest.url().toString(), presignedRequest.expiration());
    } catch (SdkException exception) {
      logFailure("presign", properties.bucket(), stagingObjectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  @Override
  public String promote(String key) {
    if (ImageStorageKeys.isFinalKey(key)) {
      return key;
    }
    if (!ImageStorageKeys.isStagingKey(key)) {
      throw new BusinessException(ProductErrorCode.IMAGE_INVALID);
    }

    String finalKey = ImageStorageKeys.toFinalKey(key);
    String stagingObjectKey = ImageStorageKeys.toStagingObjectKey(key, properties.stagingPrefix());
    String finalObjectKey = ImageStorageKeys.toFinalObjectKey(finalKey, properties.finalPrefix());
    verifyUploadedImage(stagingObjectKey, finalKey);

    CopyObjectRequest request =
        CopyObjectRequest.builder()
            .sourceBucket(properties.bucket())
            .sourceKey(stagingObjectKey)
            .destinationBucket(properties.bucket())
            .destinationKey(finalObjectKey)
            .build();
    try {
      s3Client.copyObject(request);
      return finalKey;
    } catch (NoSuchKeyException exception) {
      throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
      }
      logFailure("promote", properties.bucket(), finalObjectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    } catch (SdkException exception) {
      logFailure("promote", properties.bucket(), finalObjectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  @Override
  public URI presignDownload(String key) {
    if (!ImageStorageKeys.isFinalKey(key)) {
      throw new BusinessException(ProductErrorCode.IMAGE_INVALID);
    }

    String objectKey = ImageStorageKeys.toFinalObjectKey(key, properties.finalPrefix());
    GetObjectRequest getObjectRequest =
        GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build();
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(properties.presignExpiry())
            .getObjectRequest(getObjectRequest)
            .build();

    try {
      PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
      return URI.create(presignedRequest.url().toString());
    } catch (SdkException exception) {
      logFailure("presignDownload", properties.bucket(), objectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  private void verifyUploadedImage(String stagingObjectKey, String finalKey) {
    HeadObjectResponse object = headStagingObject(stagingObjectKey);
    if (object.contentLength() > properties.maxUploadSize().toBytes()) {
      throw new BusinessException(ProductErrorCode.IMAGE_INVALID);
    }

    String contentType = object.contentType();
    String extension =
        extensionOf(contentType)
            .orElseThrow(() -> new BusinessException(ProductErrorCode.IMAGE_INVALID));
    if (!finalKey.endsWith("." + extension)) {
      throw new BusinessException(ProductErrorCode.IMAGE_INVALID);
    }

    byte[] head = readObjectHead(stagingObjectKey);
    if (!matchesSignature(contentType, head)) {
      throw new BusinessException(ProductErrorCode.IMAGE_INVALID);
    }
  }

  private byte[] readObjectHead(String objectKey) {
    GetObjectRequest request =
        GetObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .range("bytes=0-" + (SIGNATURE_PROBE_LENGTH - 1))
            .build();

    try {
      return s3Client.getObjectAsBytes(request).asByteArray();
    } catch (SdkException exception) {
      logFailure("probe", properties.bucket(), objectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  private static Optional<String> extensionOf(String contentType) {
    if (contentType == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(CONTENT_TYPE_EXTENSIONS.get(contentType));
  }

  private static boolean matchesSignature(String contentType, byte[] head) {
    return switch (contentType) {
      case "image/jpeg" -> startsWith(head, JPEG_SIGNATURE, 0);
      case "image/png" -> startsWith(head, PNG_SIGNATURE, 0);
      case "image/webp" ->
          startsWith(head, WEBP_CONTAINER_SIGNATURE, 0)
              && startsWith(head, WEBP_FORMAT_SIGNATURE, WEBP_FORMAT_OFFSET);
      default -> false;
    };
  }

  private static boolean startsWith(byte[] head, byte[] signature, int offset) {
    if (head.length < offset + signature.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if (head[offset + i] != signature[i]) {
        return false;
      }
    }
    return true;
  }

  private HeadObjectResponse headStagingObject(String objectKey) {
    HeadObjectRequest request =
        HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build();

    try {
      return s3Client.headObject(request);
    } catch (NoSuchKeyException exception) {
      throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
      }
      // ListBucket 미부여 IAM에서는 없는 키 조회가 403으로 온다 — 권한 오류와 구분 불가라 warn만 남기고 404 계약 유지
      if (exception.statusCode() == 403) {
        log.warn(
            "S3 image head returned 403. bucket={}, key={}, requestId={}",
            properties.bucket(),
            objectKey,
            exception.requestId());
        throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
      }
      logFailure("head", properties.bucket(), objectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    } catch (SdkException exception) {
      logFailure("head", properties.bucket(), objectKey, exception);
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  private void logFailure(String operation, String bucket, String key, SdkException exception) {
    if (exception instanceof S3Exception s3Exception) {
      log.error(
          "S3 image operation failed. operation={}, bucket={}, key={}, status={}, requestId={}",
          operation,
          bucket,
          key,
          s3Exception.statusCode(),
          s3Exception.requestId(),
          exception);
      return;
    }
    log.error(
        "S3 image operation failed. operation={}, bucket={}, key={}",
        operation,
        bucket,
        key,
        exception);
  }
}
