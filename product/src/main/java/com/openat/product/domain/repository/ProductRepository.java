package com.openat.product.domain.repository;

import com.openat.product.domain.model.Product;

public interface ProductRepository {
  Product save(Product product);
}
