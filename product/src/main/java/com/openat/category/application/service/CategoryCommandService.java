package com.openat.category.application.service;

import com.openat.category.application.dto.CategoryCreateCommand;
import com.openat.category.application.dto.CategoryUpdateCommand;
import com.openat.category.application.usecase.CategoryCommandUseCase;
import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import com.openat.common.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryCommandService implements CategoryCommandUseCase {

  private final CategoryRepository categoryRepository;
  private final CategoryQueryUseCase categoryQueryUseCase;

  @Override
  public UUID create(CategoryCreateCommand command) {
    if (categoryRepository.existsByName(command.name())) {
      throw new BusinessException(CategoryErrorCode.DUPLICATE_NAME);
    }
    Category newCategory = Category.create().name(command.name()).build();
    return categoryRepository.save(newCategory).getId();
  }

  @Override
  public void update(CategoryUpdateCommand command) {
    Category category = categoryQueryUseCase.getById(command.id());
    if (category.getName().equals(command.name())) {
      return;
    }
    if (categoryRepository.existsByName(command.name())) {
      throw new BusinessException(CategoryErrorCode.DUPLICATE_NAME);
    }
    category.update(command.name());
  }

  @Override
  public void delete(UUID id) {
    Category category = categoryQueryUseCase.getById(id);
    categoryRepository.delete(category);
  }
}
