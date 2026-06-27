package com.openat.product.application.service;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.model.Category;
import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
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
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public UUID create(ProductCreateCommand command) {
    // TODO: 현재 memberId가 임시로 바인딩됨 - 연동 시 게이트웨이가 판매자 토큰으로 보증한 sellerId를 받는다 (product 자체 소유권 검증 불필요)
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

    product.update(
        command.name(), command.description(), category, command.price(), command.thumbnailKey());
  }

  @Override
  public void delete(UUID id, UUID sellerId) {
    Product product = getOwnedProduct(id, sellerId);
    productRepository.delete(product);
    eventPublisher.publishEvent(new ProductDeletedEvent(id));
  }

  private Category toCategory(UUID categoryId) {
    if (categoryId == null) {
      return null;
    }
    return categoryQueryUseCase.getById(categoryId);
  }

  private Product getOwnedProduct(UUID id, UUID sellerId) {
    // TODO: 회원-판매자 소유는 게이트웨이(판매자 토큰)가 보증하므로 product 검증 불필요 (이 메서드는 상품-판매자 매핑만 검증)
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
