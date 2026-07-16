package com.openat.productimport.infrastructure.source;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.domain.model.ProductImportSourceType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductImportSourceResolver {

  private final List<ProductImportSource> sources;

  public ProductImportSource resolve(ProductImportSourceType sourceType) {
    return sources.stream()
        .filter(source -> source.type() == sourceType)
        .findFirst()
        .orElseThrow(() -> new BusinessException(ProductImportErrorCode.INVALID_SOURCE));
  }
}
