package com.openat.productimport.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProductImportSourceTest {

  @TempDir Path tempDir;

  @Test
  void readsFileBelowAllowedPackageRoot() throws Exception {
    Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
    Path productPackage = Files.createDirectory(allowed.resolve("package"));
    Files.writeString(productPackage.resolve("products.csv"), "header");
    Path images = Files.createDirectory(productPackage.resolve("images"));
    Files.write(images.resolve("0001.jpg"), new byte[] {1, 2, 3});
    LocalProductImportSource source = source(allowed);

    source.validateLocation(productPackage.toString());
    byte[] bytes = source.read(productPackage.toString(), "images/0001.jpg", 10);

    assertThat(bytes).containsExactly(1, 2, 3);
  }

  @Test
  void rejectsPackageOutsideAllowedRoot() throws Exception {
    Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
    Path outside = Files.createDirectory(tempDir.resolve("outside"));
    Files.writeString(outside.resolve("products.csv"), "header");
    LocalProductImportSource source = source(allowed);

    assertThatThrownBy(() -> source.validateLocation(outside.toString()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.SOURCE_NOT_ALLOWED));
  }

  @Test
  void rejectsPathTraversal() throws Exception {
    Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
    Path productPackage = Files.createDirectory(allowed.resolve("package"));
    Files.writeString(productPackage.resolve("products.csv"), "header");
    Files.writeString(allowed.resolve("secret.jpg"), "secret");
    LocalProductImportSource source = source(allowed);

    assertThatThrownBy(() -> source.read(productPackage.toString(), "../secret.jpg", 100))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.INVALID_SOURCE));
  }

  @Test
  void rejectsOversizedFileBeforeReading() throws Exception {
    Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
    Path productPackage = Files.createDirectory(allowed.resolve("package"));
    Files.writeString(productPackage.resolve("products.csv"), "header");
    Files.write(productPackage.resolve("large.jpg"), new byte[] {1, 2, 3});
    LocalProductImportSource source = source(allowed);

    assertThatThrownBy(() -> source.read(productPackage.toString(), "large.jpg", 2))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.FILE_TOO_LARGE));
  }

  private static LocalProductImportSource source(Path allowedRoot) {
    return new LocalProductImportSource(
        new ProductImportProperties(
            1000,
            5 * 1024 * 1024,
            10 * 1024 * 1024,
            1,
            List.of(allowedRoot.toString()),
            List.of(),
            "ap-northeast-2"));
  }
}
