package com.openat.productimport.application.service;

import com.openat.drop.application.dto.DropCreateCommand;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.productimport.application.dto.ProductImportRow;
import com.openat.productimport.application.dto.ProductImportRowResult;
import com.openat.productimport.domain.model.ProductImportReceipt;
import com.openat.productimport.infrastructure.persistence.ProductImportReceiptRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductImportRegistrationService {

  private final ProductCommandUseCase productCommandUseCase;
  private final DropCommandUseCase dropCommandUseCase;
  private final ProductImportReceiptRepository receiptRepository;

  @Transactional
  public ProductImportRowResult register(
      UUID sellerId, ProductImportRow row, String thumbnailKey, List<String> imageKeys) {
    return receiptRepository
        .findBySellerIdAndExternalId(sellerId, row.externalId())
        .map(receipt -> ProductImportRowResult.skipped(receipt.getProductId(), receipt.getDropId()))
        .orElseGet(() -> registerNew(sellerId, row, thumbnailKey, imageKeys));
  }

  private ProductImportRowResult registerNew(
      UUID sellerId, ProductImportRow row, String thumbnailKey, List<String> imageKeys) {
    UUID productId =
        productCommandUseCase.create(
            new ProductCreateCommand(
                sellerId,
                row.name(),
                row.description(),
                row.categoryId(),
                row.price(),
                thumbnailKey,
                imageKeys));

    UUID dropId = null;
    if (row.hasDrop()) {
      dropId =
          dropCommandUseCase.create(
              new DropCreateCommand(
                  sellerId,
                  productId,
                  row.dropPrice(),
                  row.totalQuantity(),
                  row.limitPerUser(),
                  row.openAt(),
                  row.closeAt()));
    }

    receiptRepository.saveAndFlush(
        ProductImportReceipt.create(sellerId, row.externalId(), productId, dropId));
    return ProductImportRowResult.imported(productId, dropId);
  }
}
