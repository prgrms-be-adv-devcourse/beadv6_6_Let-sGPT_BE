package com.openat.product.infrastructure.storage;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "product.image.storage", havingValue = "local", matchIfMissing = true)
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
    String key = ImageStorageKeys.newKey(originalFilename);
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
}
