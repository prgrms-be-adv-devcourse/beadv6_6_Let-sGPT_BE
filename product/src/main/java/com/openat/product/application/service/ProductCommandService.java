package com.openat.product.application.service;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.model.Category;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.usecase.ProductCommandUseCase;
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
    Category category = categoryQueryUseCase.getById(command.categoryId());

    Product product =
        Product.create()
            .sellerId(command.sellerId())
            .name(command.name())
            .description(command.description())
            .category(category)
            .price(command.price())
            .thumbnailKey(command.thumbnailKey())
            .build();

    return productRepository.save(product).getId();
  }
}
