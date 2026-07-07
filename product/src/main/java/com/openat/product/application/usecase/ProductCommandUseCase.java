package com.openat.product.application.usecase;

import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import java.util.UUID;

public interface ProductCommandUseCase {
  UUID create(ProductCreateCommand command);

  void update(ProductUpdateCommand command);

  void delete(UUID id, UUID sellerId);
}
