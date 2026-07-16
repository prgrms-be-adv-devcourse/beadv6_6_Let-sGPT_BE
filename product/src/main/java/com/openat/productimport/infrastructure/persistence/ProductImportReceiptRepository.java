package com.openat.productimport.infrastructure.persistence;

import com.openat.productimport.domain.model.ProductImportReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImportReceiptRepository extends JpaRepository<ProductImportReceipt, UUID> {
  Optional<ProductImportReceipt> findBySellerIdAndExternalId(UUID sellerId, String externalId);
}
