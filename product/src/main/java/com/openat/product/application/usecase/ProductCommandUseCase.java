package com.openat.product.application.usecase;

import com.openat.product.application.dto.ProductCreateCommand;
import java.util.UUID;

public interface ProductCommandUseCase {
  UUID create(ProductCreateCommand command);
}
