package com.openat.category.application.usecase;

import com.openat.category.application.dto.CategoryCreateCommand;
import com.openat.category.application.dto.CategoryUpdateCommand;
import java.util.UUID;

public interface CategoryCommandUseCase {
  UUID create(CategoryCreateCommand command);

  void update(CategoryUpdateCommand command);

  void delete(UUID id);
}
