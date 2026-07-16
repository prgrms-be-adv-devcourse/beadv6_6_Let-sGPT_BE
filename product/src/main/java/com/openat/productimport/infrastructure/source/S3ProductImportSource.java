package com.openat.productimport.infrastructure.source;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.domain.model.ProductImportSourceType;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Component
public class S3ProductImportSource implements ProductImportSource {

  private final S3Client s3Client;
  private final List<String> allowedBuckets;

  public S3ProductImportSource(S3Client s3Client, ProductImportProperties properties) {
    this.s3Client = s3Client;
    this.allowedBuckets = properties.s3AllowedBuckets();
  }

  @Override
  public ProductImportSourceType type() {
    return ProductImportSourceType.S3;
  }

  @Override
  public void validateLocation(String location) {
    parseLocation(location);
  }

  @Override
  public byte[] read(String location, String relativePath, long maxBytes) {
    S3Location source = parseLocation(location);
    String key = joinKey(source.prefix(), safeRelativeKey(relativePath));
    try {
      long contentLength =
          s3Client
              .headObject(HeadObjectRequest.builder().bucket(source.bucket()).key(key).build())
              .contentLength();
      if (contentLength > maxBytes) {
        throw new BusinessException(
            ProductImportErrorCode.FILE_TOO_LARGE, "%s 파일이 제한 크기를 초과했습니다.".formatted(relativePath));
      }
      byte[] content =
          s3Client
              .getObjectAsBytes(GetObjectRequest.builder().bucket(source.bucket()).key(key).build())
              .asByteArray();
      if (content.length > maxBytes) {
        throw new BusinessException(ProductImportErrorCode.FILE_TOO_LARGE);
      }
      return content;
    } catch (BusinessException exception) {
      throw exception;
    } catch (NoSuchKeyException exception) {
      throw invalidSource("S3 파일을 찾을 수 없습니다: " + relativePath, exception);
    } catch (SdkException exception) {
      throw invalidSource("S3 파일을 읽을 수 없습니다: " + relativePath, exception);
    }
  }

  private S3Location parseLocation(String location) {
    try {
      URI uri = URI.create(location);
      if (!"s3".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("s3://bucket/prefix 형식이어야 합니다.");
      }
      String bucket = uri.getHost();
      if (allowedBuckets.isEmpty() || !allowedBuckets.contains(bucket)) {
        throw new BusinessException(
            ProductImportErrorCode.SOURCE_NOT_ALLOWED, "허용되지 않은 S3 버킷입니다: " + bucket);
      }
      String path = uri.getPath() == null ? "" : uri.getPath();
      String prefix = path.startsWith("/") ? path.substring(1) : path;
      prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
      if (!prefix.isBlank()) {
        prefix = safeRelativeKey(prefix);
      }
      return new S3Location(bucket, prefix);
    } catch (BusinessException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalidSource("S3 위치는 s3://bucket/prefix 형식이어야 합니다.", exception);
    }
  }

  private static String safeRelativeKey(String value) {
    if (value == null || value.isBlank() || value.startsWith("/")) {
      throw invalidSource("빈 경로나 절대 경로는 사용할 수 없습니다.", null);
    }
    List<String> segments = new ArrayList<>();
    for (String segment : value.replace('\\', '/').split("/")) {
      if (segment.isBlank() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        throw invalidSource("상위 경로는 사용할 수 없습니다: " + value, null);
      }
      segments.add(segment);
    }
    if (segments.isEmpty()) {
      throw invalidSource("빈 파일 경로입니다.", null);
    }
    return String.join("/", segments);
  }

  private static String joinKey(String prefix, String relativePath) {
    return prefix == null || prefix.isBlank() ? relativePath : prefix + "/" + relativePath;
  }

  private static BusinessException invalidSource(String message, Throwable cause) {
    return new BusinessException(ProductImportErrorCode.INVALID_SOURCE, message, cause);
  }

  private record S3Location(String bucket, String prefix) {}
}
