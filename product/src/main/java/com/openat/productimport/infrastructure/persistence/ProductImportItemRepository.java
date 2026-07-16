package com.openat.productimport.infrastructure.persistence;

import com.openat.productimport.domain.model.ProductImportItem;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImportItemRepository extends JpaRepository<ProductImportItem, UUID> {
  Page<ProductImportItem> findAllByJobId(UUID jobId, Pageable pageable);
}
