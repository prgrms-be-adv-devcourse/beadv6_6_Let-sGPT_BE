package com.openat.productimport.presentation.dto;

import com.openat.productimport.domain.model.ProductImportItem;
import com.openat.productimport.domain.model.ProductImportItemStatus;
import java.util.UUID;

public record ProductImportItemResponse(
    int rowNumber,
    String externalId,
    ProductImportItemStatus status,
    UUID productId,
    UUID dropId,
    String message) {

  public static ProductImportItemResponse from(ProductImportItem item) {
    return new ProductImportItemResponse(
        item.getRowNumber(),
        item.getExternalId(),
        item.getStatus(),
        item.getProductId(),
        item.getDropId(),
        item.getMessage());
  }
}
