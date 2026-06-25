package com.openat.drop.application.usecase;

import com.openat.drop.application.dto.DropStockCommand;

public interface DropStockUseCase {
  long deduct(DropStockCommand command);
}
