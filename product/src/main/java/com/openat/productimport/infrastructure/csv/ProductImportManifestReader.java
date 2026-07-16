package com.openat.productimport.infrastructure.csv;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.application.dto.ParsedProductImportRow;
import com.openat.productimport.application.dto.ProductImportRow;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

@Component
public class ProductImportManifestReader {

  public static final List<String> HEADERS =
      List.of(
          "external_id",
          "name",
          "description",
          "category_id",
          "category_name",
          "price",
          "thumbnail_file",
          "image_files",
          "drop_price",
          "total_quantity",
          "limit_per_user",
          "open_at",
          "close_at");

  private final int maxRows;

  public ProductImportManifestReader(ProductImportProperties properties) {
    this.maxRows = properties.maxRows();
  }

  public List<ParsedProductImportRow> read(byte[] bytes) {
    String csv = decodeUtf8(bytes);
    if (csv.startsWith("\uFEFF")) {
      csv = csv.substring(1);
    }

    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

    try (CSVParser parser = format.parse(new StringReader(csv))) {
      validateHeaders(parser);
      List<ParsedProductImportRow> rows = new ArrayList<>();
      for (CSVRecord record : parser) {
        if (rows.size() >= maxRows) {
          throw new BusinessException(
              ProductImportErrorCode.ROW_LIMIT_EXCEEDED,
              "products.csv는 최대 %d행까지 처리할 수 있습니다.".formatted(maxRows));
        }
        rows.add(parse(record));
      }
      return rows;
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException | IllegalArgumentException exception) {
      throw new BusinessException(
          ProductImportErrorCode.INVALID_MANIFEST,
          "products.csv를 해석할 수 없습니다: " + rootMessage(exception),
          exception);
    }
  }

  private void validateHeaders(CSVParser parser) {
    Set<String> actual = parser.getHeaderMap().keySet();
    List<String> missing = HEADERS.stream().filter(header -> !actual.contains(header)).toList();
    if (!missing.isEmpty()) {
      throw new BusinessException(
          ProductImportErrorCode.INVALID_MANIFEST,
          "products.csv에 필수 열이 없습니다: " + String.join(", ", missing));
    }
  }

  private ParsedProductImportRow parse(CSVRecord record) {
    int rowNumber = Math.toIntExact(record.getRecordNumber() + 1);
    String externalId = value(record, "external_id");
    try {
      requireText(externalId, "external_id");
      if (externalId.length() > 100) {
        throw new IllegalArgumentException("external_id는 100자 이하여야 합니다.");
      }

      String name = value(record, "name");
      requireText(name, "name");
      if (name.length() > 100) {
        throw new IllegalArgumentException("name은 100자 이하여야 합니다.");
      }

      long price = positiveLong(value(record, "price"), "price");
      String thumbnailFile = value(record, "thumbnail_file");
      requireText(thumbnailFile, "thumbnail_file");

      Integer totalQuantity =
          optionalPositiveInt(value(record, "total_quantity"), "total_quantity");
      Long dropPrice = optionalPositiveLong(value(record, "drop_price"), "drop_price");
      Integer limitPerUser = optionalPositiveInt(value(record, "limit_per_user"), "limit_per_user");
      Instant openAt = optionalInstant(value(record, "open_at"), "open_at");
      Instant closeAt = optionalInstant(value(record, "close_at"), "close_at");

      if (totalQuantity == null
          && (dropPrice != null || limitPerUser != null || openAt != null || closeAt != null)) {
        throw new IllegalArgumentException("드롭 필드를 사용하려면 total_quantity를 입력해야 합니다.");
      }
      if (totalQuantity != null && openAt == null) {
        throw new IllegalArgumentException("드롭 등록 시 open_at은 필수입니다.");
      }
      if (openAt != null && closeAt != null && !closeAt.isAfter(openAt)) {
        throw new IllegalArgumentException("close_at은 open_at보다 뒤여야 합니다.");
      }

      ProductImportRow row =
          new ProductImportRow(
              rowNumber,
              externalId,
              name,
              nullIfBlank(value(record, "description")),
              optionalUuid(value(record, "category_id"), "category_id"),
              nullIfBlank(value(record, "category_name")),
              price,
              thumbnailFile,
              imageFiles(value(record, "image_files")),
              totalQuantity == null ? null : (dropPrice == null ? price : dropPrice),
              totalQuantity,
              limitPerUser,
              openAt,
              closeAt);
      return ParsedProductImportRow.valid(row);
    } catch (RuntimeException exception) {
      return ParsedProductImportRow.invalid(rowNumber, externalId, rootMessage(exception));
    }
  }

  private static List<String> imageFiles(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    Set<String> unique = new LinkedHashSet<>();
    Arrays.stream(value.split(";"))
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .forEach(unique::add);
    return List.copyOf(unique);
  }

  private static String value(CSVRecord record, String header) {
    return record.isSet(header) ? record.get(header).trim() : "";
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "은(는) 필수입니다.");
    }
  }

  private static long positiveLong(String value, String field) {
    Long parsed = optionalPositiveLong(value, field);
    if (parsed == null) {
      throw new IllegalArgumentException(field + "은(는) 필수입니다.");
    }
    return parsed;
  }

  private static Long optionalPositiveLong(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0) {
        throw new IllegalArgumentException(field + "은(는) 양수여야 합니다.");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(field + "은(는) 정수여야 합니다.", exception);
    }
  }

  private static Integer optionalPositiveInt(String value, String field) {
    Long parsed = optionalPositiveLong(value, field);
    if (parsed == null) {
      return null;
    }
    if (parsed > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(field + " 값이 너무 큽니다.");
    }
    return parsed.intValue();
  }

  private static UUID optionalUuid(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + "은(는) UUID 형식이어야 합니다.", exception);
    }
  }

  private static Instant optionalInstant(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(field + "은(는) UTC ISO-8601 형식이어야 합니다.", exception);
    }
  }

  private static String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new BusinessException(
          ProductImportErrorCode.INVALID_MANIFEST, "products.csv는 UTF-8 인코딩이어야 합니다.", exception);
    }
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }
}
