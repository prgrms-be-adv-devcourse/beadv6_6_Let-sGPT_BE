package com.openat.productimport.infrastructure.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductImportManifestReaderTest {

  private final ProductImportManifestReader reader =
      new ProductImportManifestReader(properties(1000));

  @Test
  void readsUtf8KoreanProductAndDropFields() {
    UUID categoryId = UUID.randomUUID();
    String csv =
        String.join(
            "\n",
            String.join(",", ProductImportManifestReader.HEADERS),
            "demo-0001,\"서울 한정판, 기계식 시계\",한국어 설명,%s,,189000,images/0001.jpg,,179000,50,2,2030-01-01T00:00:00Z,2030-01-08T00:00:00Z"
                .formatted(categoryId));

    var rows = reader.read(csv.getBytes(StandardCharsets.UTF_8));

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().isValid()).isTrue();
    assertThat(rows.getFirst().row().name()).isEqualTo("서울 한정판, 기계식 시계");
    assertThat(rows.getFirst().row().categoryId()).isEqualTo(categoryId);
    assertThat(rows.getFirst().row().dropPrice()).isEqualTo(179000L);
    assertThat(rows.getFirst().row().totalQuantity()).isEqualTo(50);
    assertThat(rows.getFirst().row().openAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
  }

  @Test
  void keepsInvalidRowAsRowLevelFailure() {
    String csv =
        String.join(
            "\n",
            String.join(",", ProductImportManifestReader.HEADERS),
            "demo-0001,상품,설명,,,-1,images/0001.jpg,,,,,,");

    var rows = reader.read(csv.getBytes(StandardCharsets.UTF_8));

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().isValid()).isFalse();
    assertThat(rows.getFirst().externalId()).isEqualTo("demo-0001");
    assertThat(rows.getFirst().errorMessage()).contains("price");
  }

  @Test
  void rejectsMissingRequiredHeader() {
    byte[] csv = "external_id,name\ndemo-0001,상품\n".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> reader.read(csv))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.INVALID_MANIFEST));
  }

  @Test
  void rejectsRowsOverConfiguredLimit() {
    ProductImportManifestReader oneRowReader = new ProductImportManifestReader(properties(1));
    String csv =
        String.join(
            "\n",
            String.join(",", ProductImportManifestReader.HEADERS),
            "one,상품1,설명,,,1000,images/1.jpg,,,,,,",
            "two,상품2,설명,,,1000,images/2.jpg,,,,,,");

    assertThatThrownBy(() -> oneRowReader.read(csv.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(ProductImportErrorCode.ROW_LIMIT_EXCEEDED));
  }

  private static ProductImportProperties properties(int maxRows) {
    return new ProductImportProperties(
        maxRows, 5 * 1024 * 1024, 10 * 1024 * 1024, 1, List.of(), List.of(), "ap-northeast-2");
  }
}
