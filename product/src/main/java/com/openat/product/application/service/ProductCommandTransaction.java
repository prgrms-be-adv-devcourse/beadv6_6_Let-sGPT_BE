package com.openat.product.application.service;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.model.Category;
import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.support.lock.SearchProjectionReferenceLock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProductCommandTransaction {

  private final ProductRepository productRepository;
  private final CategoryQueryUseCase categoryQueryUseCase;
  private final ApplicationEventPublisher eventPublisher;
  private final SearchProjectionReferenceLock searchProjectionReferenceLock;

  @Transactional(readOnly = true)
  public void validateCreate(UUID categoryId) {
    toCategory(categoryId);
  }

  @Transactional(readOnly = true)
  public void validateUpdate(UUID productId, UUID sellerId, UUID categoryId) {
    getOwnedProduct(productId, sellerId);
    toCategory(categoryId);
  }

  @Transactional
  public UUID create(
      ProductCreateCommand command, String thumbnailKey, List<String> imageKeys) {
    lockSearchProjectionReferences(command.sellerId(), command.categoryId());
    Category category = toCategory(command.categoryId());
    Product newProduct =
        Product.create()
            .sellerId(command.sellerId())
            .name(command.name())
            .description(command.description())
            .category(category)
            .price(command.price())
            .thumbnailKey(thumbnailKey)
            .imageKeys(imageKeys)
            .build();

    Product product = productRepository.save(newProduct);
    eventPublisher.publishEvent(new ProductCreatedEvent(product));
    return product.getId();
  }

  @Transactional
  public void update(
      ProductUpdateCommand command, String thumbnailKey, List<String> imageKeys) {
    lockSearchProjectionReferences(command.sellerId(), command.categoryId());
    Product product = getOwnedProductForUpdate(command.id(), command.sellerId());
    Category category = toCategory(command.categoryId());
    product.update(
        command.name(), command.description(), category, command.price(), thumbnailKey, imageKeys);
    eventPublisher.publishEvent(new ProductUpdatedEvent(product));
  }

  @Transactional
  public void delete(UUID id, UUID sellerId) {
    Product product = getOwnedProductForUpdate(id, sellerId);
    product.refreshSearchProjection();
    long aggregateSequence = product.currentSearchSnapshotSequence();
    productRepository.delete(product);
    eventPublisher.publishEvent(new ProductDeletedEvent(id, aggregateSequence, Instant.now()));
  }

  private Category toCategory(UUID categoryId) {
    if (categoryId == null) {
      return null;
    }
    return categoryQueryUseCase.getById(categoryId);
  }

  private Product getOwnedProduct(UUID id, UUID sellerId) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(() -> new BusinessException(ProductErrorCode.NOT_FOUND));
    validateOwner(product, sellerId);
    return product;
  }

  private Product getOwnedProductForUpdate(UUID id, UUID sellerId) {
    Product product =
        productRepository
            .findByIdForUpdate(id)
            .orElseThrow(() -> new BusinessException(ProductErrorCode.NOT_FOUND));
    validateOwner(product, sellerId);
    return product;
  }

  private void validateOwner(Product product, UUID sellerId) {
    if (!product.getSellerId().equals(sellerId)) {
      throw new BusinessException(ProductErrorCode.NOT_OWNER);
    }
  }

  private void lockSearchProjectionReferences(UUID sellerId, UUID categoryId) {
    searchProjectionReferenceLock.lockSellerForSnapshotRead(sellerId);
    if (categoryId != null) {
      searchProjectionReferenceLock.lockCategoryForSnapshotRead(categoryId);
    }
  }
}
