package com.openat.search.product.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.search.product.application.dto.ProductInfo;
import com.openat.search.product.application.usecase.ProductQueryUseCase;
import com.openat.search.product.domain.model.Product;
import com.openat.search.product.domain.repository.ProductRepository;
import com.openat.search.product.domain.repository.ProductSearchCondition;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService implements ProductQueryUseCase {

  private final ProductRepository productRepository;

  @Override
  public ProductInfo getById(UUID id) {
    return ProductInfo.from(getProductOrThrow(id), null);
  }

  @Override
  public Page<ProductInfo> searchProducts(ProductSearchCondition condition, Pageable pageable) {
    return productRepository
        .search(condition, pageable)
        .map(product -> ProductInfo.from(product, null));
  }

  private Product getProductOrThrow(UUID id) {
    return productRepository
        .findById(id)
        .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
  }
}
