package com.openat.productimport.infrastructure.source;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.domain.model.ProductImportSourceType;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalProductImportSource implements ProductImportSource {

  private static final String MANIFEST_FILE = "products.csv";

  private final List<Path> allowedRoots;

  public LocalProductImportSource(ProductImportProperties properties) {
    this.allowedRoots =
        properties.localAllowedRoots().stream()
            .map(LocalProductImportSource::normalizedRoot)
            .toList();
  }

  @Override
  public ProductImportSourceType type() {
    return ProductImportSourceType.LOCAL;
  }

  @Override
  public void validateLocation(String location) {
    Path packageRoot = packageRoot(location);
    resolveRegularFile(packageRoot, MANIFEST_FILE);
  }

  @Override
  public byte[] read(String location, String relativePath, long maxBytes) {
    Path file = resolveRegularFile(packageRoot(location), relativePath);
    try {
      long size = Files.size(file);
      if (size > maxBytes) {
        throw new BusinessException(
            ProductImportErrorCode.FILE_TOO_LARGE, "%s 파일이 제한 크기를 초과했습니다.".formatted(relativePath));
      }
      byte[] content = Files.readAllBytes(file);
      if (content.length > maxBytes) {
        throw new BusinessException(ProductImportErrorCode.FILE_TOO_LARGE);
      }
      return content;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw invalidSource("파일을 읽을 수 없습니다: " + relativePath, exception);
    }
  }

  private Path packageRoot(String location) {
    if (location == null || location.isBlank()) {
      throw new BusinessException(ProductImportErrorCode.INVALID_SOURCE);
    }
    try {
      Path root = Path.of(location).toAbsolutePath().normalize().toRealPath();
      if (!Files.isDirectory(root)) {
        throw invalidSource("폴더가 아닙니다: " + location, null);
      }
      if (allowedRoots.isEmpty() || allowedRoots.stream().noneMatch(root::startsWith)) {
        throw new BusinessException(
            ProductImportErrorCode.SOURCE_NOT_ALLOWED, "허용된 로컬 상위 폴더 밖의 경로입니다.");
      }
      return root;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw invalidSource("로컬 폴더를 확인할 수 없습니다: " + location, exception);
    }
  }

  private Path resolveRegularFile(Path packageRoot, String relativePath) {
    Path relative = safeRelativePath(relativePath);
    try {
      Path resolved = packageRoot.resolve(relative).normalize().toRealPath();
      if (!resolved.startsWith(packageRoot) || !Files.isRegularFile(resolved)) {
        throw invalidSource("파일을 찾을 수 없습니다: " + relativePath, null);
      }
      return resolved;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw invalidSource("파일을 찾을 수 없습니다: " + relativePath, exception);
    }
  }

  private static Path safeRelativePath(String value) {
    if (value == null || value.isBlank()) {
      throw invalidSource("빈 파일 경로입니다.", null);
    }
    try {
      Path relative = Path.of(value.replace('/', java.io.File.separatorChar)).normalize();
      if (relative.isAbsolute() || relative.startsWith("..")) {
        throw invalidSource("상대 경로만 사용할 수 있습니다: " + value, null);
      }
      return relative;
    } catch (RuntimeException exception) {
      if (exception instanceof BusinessException businessException) {
        throw businessException;
      }
      throw invalidSource("잘못된 파일 경로입니다: " + value, exception);
    }
  }

  private static Path normalizedRoot(String value) {
    Path path = Path.of(value).toAbsolutePath().normalize();
    try {
      return path.toRealPath();
    } catch (IOException ignored) {
      return path;
    }
  }

  private static BusinessException invalidSource(String message, Throwable cause) {
    return new BusinessException(ProductImportErrorCode.INVALID_SOURCE, message, cause);
  }
}
