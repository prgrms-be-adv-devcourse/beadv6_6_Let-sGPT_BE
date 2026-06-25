package com.openat.product.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductInfo;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
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
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(() -> new BusinessException(ProductErrorCode.NOT_FOUND));
    return ProductInfo.from(product);
  }

  @Override
  public Page<ProductInfo> searchProducts(ProductSearchCondition condition, Pageable pageable) {
    Page<Product> productPage = productRepository.search(condition, pageable);
    return productPage.map(ProductInfo::from);
  }
}
