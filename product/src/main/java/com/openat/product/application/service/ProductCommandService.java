package com.openat.product.application.service;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.model.Category;
import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductCommandService implements ProductCommandUseCase {

  private final ProductRepository productRepository;
  private final CategoryQueryUseCase categoryQueryUseCase;
  private final ImageStorageUseCase imageStorageUseCase;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public UUID create(ProductCreateCommand command) {
    Category category = toCategory(command.categoryId());
    String thumbnailKey = promoteImageKey(command.thumbnailKey());
    List<String> imageKeys = promoteImageKeys(command.imageKeys());

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

  @Override
  public void update(ProductUpdateCommand command) {
    Product product = getOwnedProduct(command.id(), command.sellerId());
    Category category = toCategory(command.categoryId());
    String thumbnailKey = promoteImageKey(command.thumbnailKey());
    List<String> imageKeys = promoteImageKeys(command.imageKeys());

    product.update(
        command.name(), command.description(), category, command.price(), thumbnailKey, imageKeys);
    eventPublisher.publishEvent(new ProductUpdatedEvent(product));
  }

  @Override
  public void delete(UUID id, UUID sellerId) {
    Product product = getOwnedProduct(id, sellerId);
    productRepository.delete(product);
    eventPublisher.publishEvent(new ProductDeletedEvent(id, Instant.now()));
  }

  private Category toCategory(UUID categoryId) {
    if (categoryId == null) {
      return null;
    }
    return categoryQueryUseCase.getById(categoryId);
  }

  private String promoteImageKey(String key) {
    if (key == null || key.isBlank()) {
      return key;
    }
    return imageStorageUseCase.promote(key);
  }

  private List<String> promoteImageKeys(List<String> imageKeys) {
    if (imageKeys == null) {
      return null;
    }
    return imageKeys.stream().map(this::promoteImageKey).toList();
  }

  private Product getOwnedProduct(UUID id, UUID sellerId) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(() -> new BusinessException(ProductErrorCode.NOT_FOUND));
    if (!product.getSellerId().equals(sellerId)) {
      throw new BusinessException(ProductErrorCode.NOT_OWNER);
    }
    return product;
  }
}
