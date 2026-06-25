package com.openat.product.application.service;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.model.Category;
import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductCommandService implements ProductCommandUseCase {

  private final ProductRepository productRepository;
  private final CategoryQueryUseCase categoryQueryUseCase;

  @Override
  public UUID create(ProductCreateCommand command) {
    Category category = toCategory(command.categoryId());

    Product newProduct =
        Product.create()
            .sellerId(command.sellerId())
            .name(command.name())
            .description(command.description())
            .category(category)
            .price(command.price())
            .thumbnailKey(command.thumbnailKey())
            .build();

    return productRepository.save(newProduct).getId();
  }

  @Override
  public void update(ProductUpdateCommand command) {
    Product product = getOwnedProduct(command.id(), command.sellerId());
    Category category = toCategory(command.categoryId());

    // TODO(drop): 진행 중(OPEN) 드롭이 걸린 상품의 가격 등 수정 제약 — drop 설계 시 결정
    product.update(
        command.name(), command.description(), category, command.price(), command.thumbnailKey());
  }

  @Override
  public void delete(UUID id, UUID sellerId) {
    Product product = getOwnedProduct(id, sellerId);

    // TODO(drop): 진행 중(OPEN) 드롭이 걸린 상품의 삭제 차단 + 자식 drop 하향 soft delete 전파(이벤트) — drop 설계 시 결정
    productRepository.delete(product);
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
    if (!product.getSellerId().equals(sellerId)) {
      throw new BusinessException(ProductErrorCode.NOT_OWNER);
    }
    return product;
  }
}
