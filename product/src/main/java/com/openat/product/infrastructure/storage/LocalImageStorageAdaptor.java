package com.openat.product.infrastructure.storage;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// TODO(final): 배포 시 AWS S3 스토리지 어댑터로 교체 (현재 세미: 부팅 서버 로컬 파일시스템 저장).
@Component
public class LocalImageStorageAdaptor implements ImageStorageUseCase {

  private final Path baseDir;

  public LocalImageStorageAdaptor(@Value("${product.image.local-dir}") String localDir) {
    this.baseDir = Path.of(localDir).toAbsolutePath().normalize();
    try {
      Files.createDirectories(baseDir);
    } catch (IOException e) {
      throw new IllegalStateException("이미지 저장 디렉터리 생성 실패: " + baseDir, e);
    }
  }

  @Override
  public String store(byte[] content, String originalFilename) {
    String key = UUID.randomUUID() + extensionOf(originalFilename);
    try {
      Files.write(baseDir.resolve(key), content);
    } catch (IOException e) {
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
    return key;
  }

  @Override
  public byte[] load(String key) {
    Path path = baseDir.resolve(key).normalize();
    if (!path.startsWith(baseDir) || !Files.isRegularFile(path)) {
      throw new BusinessException(ProductErrorCode.IMAGE_NOT_FOUND);
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }

  private String extensionOf(String originalFilename) {
    if (originalFilename == null) {
      return "";
    }
    int dot = originalFilename.lastIndexOf('.');
    if (dot < 0) {
      return "";
    }
    return originalFilename.substring(dot).toLowerCase();
  }
}
