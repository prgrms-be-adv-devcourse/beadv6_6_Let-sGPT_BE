package com.openat.product.application.usecase;

import com.openat.product.application.dto.ProductChangeInfo;
import com.openat.product.application.dto.ProductInfo;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductSearchCondition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductQueryUseCase {
  ProductInfo getById(UUID id);

  Product getOwnedProduct(UUID id, UUID sellerId);

  Page<ProductInfo> searchProducts(ProductSearchCondition condition, Pageable pageable);

  List<ProductChangeInfo> searchChanges(Instant changedAfter);
}
