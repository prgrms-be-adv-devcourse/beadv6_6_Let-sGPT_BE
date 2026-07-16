package com.openat.productimport.application.dto;

import com.openat.productimport.domain.model.ProductImportItemStatus;
import java.util.UUID;

public record ProductImportRowResult(
    ProductImportItemStatus status, UUID productId, UUID dropId, String message) {

  public static ProductImportRowResult validated() {
    return new ProductImportRowResult(ProductImportItemStatus.VALIDATED, null, null, "검증에 성공했습니다.");
  }

  public static ProductImportRowResult imported(UUID productId, UUID dropId) {
    return new ProductImportRowResult(
        ProductImportItemStatus.IMPORTED, productId, dropId, "등록에 성공했습니다.");
  }

  public static ProductImportRowResult skipped(UUID productId, UUID dropId) {
    return new ProductImportRowResult(
        ProductImportItemStatus.SKIPPED, productId, dropId, "이미 등록된 external_id입니다.");
  }
}
