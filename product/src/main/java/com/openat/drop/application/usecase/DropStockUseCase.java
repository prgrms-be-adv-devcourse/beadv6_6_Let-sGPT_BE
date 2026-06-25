package com.openat.drop.application.usecase;

import com.openat.drop.application.dto.DropStockCommand;
import java.util.Optional;

public interface DropStockUseCase {
  long deduct(DropStockCommand command);

  Optional<Long> rollback(DropStockCommand command);
}
