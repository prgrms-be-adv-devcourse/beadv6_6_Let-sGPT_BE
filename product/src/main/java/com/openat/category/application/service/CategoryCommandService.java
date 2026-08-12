package com.openat.category.application.service;

import com.openat.category.application.dto.CategoryCreateCommand;
import com.openat.category.application.dto.CategoryUpdateCommand;
import com.openat.category.application.usecase.CategoryCommandUseCase;
import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.category.domain.event.CategoryDeletingEvent;
import com.openat.category.domain.event.CategoryUpdatedEvent;
import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import com.openat.common.exception.BusinessException;
import com.openat.support.lock.SearchProjectionReferenceLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryCommandService implements CategoryCommandUseCase {

  private final CategoryRepository categoryRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final SearchProjectionReferenceLock searchProjectionReferenceLock;

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
    searchProjectionReferenceLock.lockCategoryForReferenceWrite(command.id());
    Category category = getCategory(command.id());
    if (category.getName().equals(command.name())) {
      return;
    }
    if (categoryRepository.existsByName(command.name())) {
      throw new BusinessException(CategoryErrorCode.DUPLICATE_NAME);
    }
    category.update(command.name());
    eventPublisher.publishEvent(new CategoryUpdatedEvent(category.getId()));
  }

  @Override
  public void delete(UUID id) {
    searchProjectionReferenceLock.lockCategoryForReferenceWrite(id);
    Category category = getCategory(id);
    eventPublisher.publishEvent(new CategoryDeletingEvent(id));
    categoryRepository.delete(category);
  }

  private Category getCategory(UUID id) {
    return categoryRepository
        .findById(id)
        .orElseThrow(() -> new BusinessException(CategoryErrorCode.NOT_FOUND));
  }
}
