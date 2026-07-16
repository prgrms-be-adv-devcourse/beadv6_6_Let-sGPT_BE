package com.openat.support.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Test product CSV")
class TestProductCsvTest {

  private static final String RESOURCE = "/testdata/test-products-10.csv";

  @Test
  @DisplayName("CSV has header plus 10 product rows")
  void csv_has10Rows() throws Exception {
    List<String[]> rows = readCsv();

    assertThat(rows).hasSize(11);
    assertThat(rows.get(0))
        .containsExactly(
            "product_id",
            "name",
            "description",
            "category",
            "thumbnail_key",
            "edition_size",
            "source_item_id",
            "source_image_id",
            "width",
            "height");
  }

  @Test
  @DisplayName("Each row has a valid thumbnail key and numeric image size")
  void csv_rowsHaveValidShape() throws Exception {
    List<String[]> rows = readCsv();

    for (int i = 1; i < rows.size(); i++) {
      String[] row = rows.get(i);
      assertThat(row).hasSize(10);
      assertThat(row[0]).matches("\\d{4}");
      assertThat(row[4]).matches("search-demo-v1/\\d{4}\\.jpg");
      assertThat(Integer.parseInt(row[5])).isPositive();
      assertThat(Integer.parseInt(row[8])).isPositive();
      assertThat(Integer.parseInt(row[9])).isPositive();
    }
  }

  @Test
  @DisplayName("Product ids and image keys are unique across the test CSV")
  void csv_valuesAreUnique() throws Exception {
    List<String[]> rows = readCsv();
    Set<String> productIds = new HashSet<>();
    Set<String> imageKeys = new HashSet<>();

    for (int i = 1; i < rows.size(); i++) {
      productIds.add(rows.get(i)[0]);
      imageKeys.add(rows.get(i)[4]);
    }

    assertThat(productIds).hasSize(10);
    assertThat(imageKeys).hasSize(10);
  }

  private List<String[]> readCsv() throws IOException {
    try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
      assertThat(input).isNotNull();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        return reader.lines().map(TestProductCsvTest::parseCsvLine).toList();
      }
    }
  }

  private static String[] parseCsvLine(String line) {
    if (!line.startsWith("\"")) {
      return line.split(",", -1);
    }
    String[] tokens = line.substring(1, line.length() - 1).split("\",\"", -1);
    for (int i = 0; i < tokens.length; i++) {
      tokens[i] = tokens[i].replace("\"\"", "\"");
    }
    return tokens;
  }
}
