package com.openat.category.application.usecase;

import com.openat.category.domain.model.Category;
import java.util.UUID;

public interface CategoryQueryUseCase {
  Category getById(UUID id);
}
