package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.category.domain.model.Category;
import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.fixture.ProductFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 명령 서비스")
class ProductCommandServiceTest {

  @InjectMocks private ProductCommandService productCommandService;
  @Mock private ProductRepository productRepository;
  @Mock private CategoryQueryUseCase categoryQueryUseCase;

  @Nested
  @DisplayName("상품 등록")
  class Create {

    @Test
    @DisplayName("카테고리 없이 등록하면 미분류 상품으로 저장한다")
    void create_categoryNull_savesUncategorized() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID savedId = UUID.randomUUID();
      ProductCreateCommand command = uncategorizedCommand(sellerId);
      given(productRepository.save(any(Product.class)))
          .willReturn(ProductFixture.persisted(savedId, sellerId));

      // when
      UUID result = productCommandService.create(command);

      // then
      assertThat(result).isEqualTo(savedId);
      then(categoryQueryUseCase).should(never()).getById(any());

      ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
      then(productRepository).should().save(productCaptor.capture());
      assertThat(productCaptor.getValue().getCategory()).isNull();
    }

    @Test
    @DisplayName("카테고리를 지정하면 조회한 카테고리로 상품을 저장한다")
    void create_categoryGiven_savesWithCategory() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID categoryId = UUID.randomUUID();
      UUID savedId = UUID.randomUUID();
      Category category = Category.create().name("의류").build();
      given(categoryQueryUseCase.getById(categoryId)).willReturn(category);
      given(productRepository.save(any(Product.class)))
          .willReturn(ProductFixture.persisted(savedId, sellerId));
      ProductCreateCommand command = categorizedCommand(sellerId, categoryId);

      // when
      UUID result = productCommandService.create(command);

      // then
      assertThat(result).isEqualTo(savedId);

      ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
      then(productRepository).should().save(productCaptor.capture());
      assertThat(productCaptor.getValue().getCategory()).isEqualTo(category);
    }

    @Test
    @DisplayName("없는 카테고리를 지정하면 예외가 전파되고 저장하지 않는다")
    void create_categoryNotFound_throwsException() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID missingCategoryId = UUID.randomUUID();
      given(categoryQueryUseCase.getById(missingCategoryId))
          .willThrow(new BusinessException(CategoryErrorCode.NOT_FOUND));
      ProductCreateCommand command = categorizedCommand(sellerId, missingCategoryId);

      // when & then
      assertThatThrownBy(() -> productCommandService.create(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", CategoryErrorCode.NOT_FOUND);
      then(productRepository).should(never()).save(any());
    }
  }

  private ProductCreateCommand uncategorizedCommand(UUID sellerId) {
    return new ProductCreateCommand(sellerId, "기본 굿즈", "설명", null, 10_000L, null);
  }

  private ProductCreateCommand categorizedCommand(UUID sellerId, UUID categoryId) {
    return new ProductCreateCommand(sellerId, "기본 굿즈", "설명", categoryId, 10_000L, null);
  }
}
