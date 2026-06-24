package com.openat.product.application.usecase;

import com.openat.product.application.dto.ProductInfo;
import com.openat.product.domain.repository.ProductSearchCondition;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductQueryUseCase {
  ProductInfo getById(UUID id);

  Page<ProductInfo> searchProducts(ProductSearchCondition condition, Pageable pageable);
}
