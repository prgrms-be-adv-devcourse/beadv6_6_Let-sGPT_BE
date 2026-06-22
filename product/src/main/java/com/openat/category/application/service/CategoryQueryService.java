package com.openat.category.application.service;

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
@Transactional(readOnly = true)
public class CategoryQueryService implements CategoryQueryUseCase {

  private final CategoryRepository categoryRepository;

  @Override
  public Category getById(UUID id) {
    return categoryRepository
        .findById(id)
        .orElseThrow(() -> new BusinessException(CategoryErrorCode.NOT_FOUND));
  }
}
