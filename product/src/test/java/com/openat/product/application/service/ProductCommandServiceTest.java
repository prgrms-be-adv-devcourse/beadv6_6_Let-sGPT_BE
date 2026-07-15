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
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.fixture.ProductFixture;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 명령 서비스")
class ProductCommandServiceTest {

  @InjectMocks private ProductCommandService productCommandService;
  @Mock private ProductRepository productRepository;
  @Mock private CategoryQueryUseCase categoryQueryUseCase;
  @Mock private ApplicationEventPublisher eventPublisher;

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
    @DisplayName("이미지 키 목록을 함께 등록하면 갤러리로 저장한다")
    void create_withImageKeys_savesGallery() {
      // given
      UUID sellerId = UUID.randomUUID();
      given(productRepository.save(any(Product.class)))
          .willReturn(ProductFixture.persisted(UUID.randomUUID(), sellerId));
      ProductCreateCommand command =
          new ProductCreateCommand(
              sellerId, "갤러리 상품", "설명", null, 10_000L, "thumb", List.of("img-1", "img-2"));

      // when
      productCommandService.create(command);

      // then
      ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
      then(productRepository).should().save(productCaptor.capture());
      assertThat(productCaptor.getValue().getImageKeys()).containsExactly("img-1", "img-2");
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

    @Test
    @DisplayName("상품을 등록하면 저장된 상품의 생성 이벤트를 발행한다")
    void create_validProduct_publishesCreatedEvent() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      Product savedProduct = ProductFixture.persisted(productId, sellerId);
      ProductCreateCommand command = uncategorizedCommand(sellerId);
      given(productRepository.save(any(Product.class))).willReturn(savedProduct);

      // when
      productCommandService.create(command);

      // then
      ArgumentCaptor<ProductCreatedEvent> eventCaptor =
          ArgumentCaptor.forClass(ProductCreatedEvent.class);
      then(eventPublisher).should().publishEvent(eventCaptor.capture());
      assertThat(eventCaptor.getValue().product()).isSameAs(savedProduct);
    }
  }

  @Nested
  @DisplayName("상품 수정")
  class Update {

    @Test
    @DisplayName("소유자가 수정하면 상품 정보가 갱신된다")
    void update_validOwner_updatesProduct() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, sellerId);
      given(productRepository.findById(productId)).willReturn(Optional.of(product));
      ProductUpdateCommand command =
          new ProductUpdateCommand(
              productId, sellerId, "수정된 상품", "수정 설명", null, 5_000L, null, null);

      // when
      productCommandService.update(command);

      // then
      assertThat(product.getName()).isEqualTo("수정된 상품");
      assertThat(product.getPrice()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("없는 상품을 수정하면 NOT_FOUND 예외를 던진다")
    void update_notFound_throwsException() {
      // given
      UUID productId = UUID.randomUUID();
      given(productRepository.findById(productId)).willReturn(Optional.empty());
      ProductUpdateCommand command =
          new ProductUpdateCommand(
              productId, UUID.randomUUID(), "수정", null, null, null, null, null);

      // when & then
      assertThatThrownBy(() -> productCommandService.update(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아니면 NOT_OWNER 예외를 던진다")
    void update_notOwner_throwsException() {
      // given
      UUID productId = UUID.randomUUID();
      UUID ownerId = UUID.randomUUID();
      UUID otherSellerId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, ownerId);
      given(productRepository.findById(productId)).willReturn(Optional.of(product));
      ProductUpdateCommand command =
          new ProductUpdateCommand(productId, otherSellerId, "수정", null, null, null, null, null);

      // when & then
      assertThatThrownBy(() -> productCommandService.update(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_OWNER);
    }

    @Test
    @DisplayName("상품을 수정하면 갱신된 상품의 수정 이벤트를 발행한다")
    void update_validOwner_publishesUpdatedEvent() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, sellerId);
      given(productRepository.findById(productId)).willReturn(Optional.of(product));
      ProductUpdateCommand command =
          new ProductUpdateCommand(
              productId, sellerId, "수정 상품", "수정 설명", null, 20_000L, null, null);

      // when
      productCommandService.update(command);

      // then
      ArgumentCaptor<ProductUpdatedEvent> eventCaptor =
          ArgumentCaptor.forClass(ProductUpdatedEvent.class);
      then(eventPublisher).should().publishEvent(eventCaptor.capture());
      assertThat(eventCaptor.getValue().product()).isSameAs(product);
      assertThat(eventCaptor.getValue().product().getName()).isEqualTo("수정 상품");
    }
  }

  @Nested
  @DisplayName("상품 삭제")
  class Delete {

    @Test
    @DisplayName("소유자가 삭제하면 상품을 삭제한다")
    void delete_validOwner_deletesProduct() {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, sellerId);
      given(productRepository.findById(productId)).willReturn(Optional.of(product));

      // when
      productCommandService.delete(productId, sellerId);

      // then
      then(productRepository).should().delete(product);
      ArgumentCaptor<ProductDeletedEvent> eventCaptor =
          ArgumentCaptor.forClass(ProductDeletedEvent.class);
      then(eventPublisher).should().publishEvent(eventCaptor.capture());
      assertThat(eventCaptor.getValue().productId()).isEqualTo(productId);
      assertThat(eventCaptor.getValue().deletedAt()).isNotNull();
    }

    @Test
    @DisplayName("없는 상품을 삭제하면 NOT_FOUND 예외를 던지고 삭제하지 않는다")
    void delete_notFound_throwsException() {
      // given
      UUID productId = UUID.randomUUID();
      given(productRepository.findById(productId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> productCommandService.delete(productId, UUID.randomUUID()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_FOUND);
      then(productRepository).should(never()).delete(any());
    }

    @Test
    @DisplayName("소유자가 아니면 NOT_OWNER 예외를 던지고 삭제하지 않는다")
    void delete_notOwner_throwsException() {
      // given
      UUID productId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, UUID.randomUUID());
      given(productRepository.findById(productId)).willReturn(Optional.of(product));

      // when & then
      assertThatThrownBy(() -> productCommandService.delete(productId, UUID.randomUUID()))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_OWNER);
      then(productRepository).should(never()).delete(any());
    }
  }

  private ProductCreateCommand uncategorizedCommand(UUID sellerId) {
    return new ProductCreateCommand(sellerId, "기본 굿즈", "설명", null, 10_000L, null, null);
  }

  private ProductCreateCommand categorizedCommand(UUID sellerId, UUID categoryId) {
    return new ProductCreateCommand(sellerId, "기본 굿즈", "설명", categoryId, 10_000L, null, null);
  }
}
