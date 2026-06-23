package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.Product;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {}
