package com.openat.search.product.infrastructure.persistence;

import com.openat.search.product.domain.model.Product;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {

  Page<Product> findBySellerId(UUID sellerId, Pageable pageable);
}
