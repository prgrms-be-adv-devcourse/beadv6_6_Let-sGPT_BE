package com.openat.search.product.application.usecase;

import com.openat.search.product.application.dto.ProductInfo;
import com.openat.search.product.domain.repository.ProductSearchCondition;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductQueryUseCase {
  ProductInfo getById(UUID id);

  Page<ProductInfo> searchProducts(ProductSearchCondition condition, Pageable pageable);
}
