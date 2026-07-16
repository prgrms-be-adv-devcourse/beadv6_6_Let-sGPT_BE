package com.openat.productimport.application.dto;

public record ParsedProductImportRow(
    int rowNumber, String externalId, ProductImportRow row, String errorMessage) {

  public static ParsedProductImportRow valid(ProductImportRow row) {
    return new ParsedProductImportRow(row.rowNumber(), row.externalId(), row, null);
  }

  public static ParsedProductImportRow invalid(
      int rowNumber, String externalId, String errorMessage) {
    return new ParsedProductImportRow(rowNumber, externalId, null, errorMessage);
  }

  public boolean isValid() {
    return row != null;
  }
}
