package com.openat.productimport.infrastructure.source;

import com.openat.productimport.domain.model.ProductImportSourceType;

public interface ProductImportSource {
  ProductImportSourceType type();

  void validateLocation(String location);

  byte[] read(String location, String relativePath, long maxBytes);
}
