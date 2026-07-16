package com.openat.productimport.infrastructure.persistence;

import com.openat.productimport.domain.model.ProductImportJob;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImportJobRepository extends JpaRepository<ProductImportJob, UUID> {}
