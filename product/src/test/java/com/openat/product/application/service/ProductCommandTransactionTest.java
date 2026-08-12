package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

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
import com.openat.product.fixture.ProductFixture;
import com.openat.support.lock.SearchProjectionReferenceLock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 명령 쓰기 트랜잭션")
class ProductCommandTransactionTest {

  @InjectMocks private ProductCommandTransaction commandTransaction;
  @Mock private ProductRepository productRepository;
  @Mock private CategoryQueryUseCase categoryQueryUseCase;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private SearchProjectionReferenceLock referenceLock;

  @Nested
  @DisplayName("사전 검증")
  class Validation {

    @Test
    @DisplayName("수정 전 상품 소유권과 카테고리를 검증한다")
    void validateUpdate_validInput_checksOwnerAndCategory() {
      UUID productId = UUID.randomUUID();
      UUID sellerId = UUID.randomUUID();
      UUID categoryId = UUID.randomUUID();
      given(productRepository.findById(productId))
          .willReturn(Optional.of(ProductFixture.persisted(productId, sellerId)));
      given(categoryQueryUseCase.getById(categoryId))
          .willReturn(Category.create().name("의류").build());

      commandTransaction.validateUpdate(productId, sellerId, categoryId);

      then(productRepository).should().findById(productId);
      then(categoryQueryUseCase).should().getById(categoryId);
      then(referenceLock).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("소유자가 아니면 이미지 승격 전 검증에서 거절한다")
    void validateUpdate_notOwner_rejects() {
      UUID productId = UUID.randomUUID();
      given(productRepository.findById(productId))
          .willReturn(
              Optional.of(ProductFixture.persisted(productId, UUID.randomUUID())));

      assertThatThrownBy(
              () ->
                  commandTransaction.validateUpdate(
                      productId, UUID.randomUUID(), null))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_OWNER);
    }
  }

  @Nested
  @DisplayName("등록")
  class Create {

    @Test
    @DisplayName("참조 잠금 뒤 카테고리를 다시 조회하고 상품과 생성 이벤트를 기록한다")
    void create_categorized_revalidatesAndPublishes() {
      UUID sellerId = UUID.randomUUID();
      UUID categoryId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      Category category = Category.create().name("의류").build();
      Product saved = ProductFixture.persisted(productId, sellerId);
      ProductCreateCommand command =
          new ProductCreateCommand(
              sellerId, "상품", "설명", categoryId, 10_000L, null, null);
      given(categoryQueryUseCase.getById(categoryId)).willReturn(category);
      given(productRepository.save(any(Product.class))).willReturn(saved);

      UUID result = commandTransaction.create(command, "final/thumb.png", List.of());

      assertThat(result).isEqualTo(productId);
      InOrder order = inOrder(referenceLock, categoryQueryUseCase, productRepository);
      order.verify(referenceLock).lockSellerForSnapshotRead(sellerId);
      order.verify(referenceLock).lockCategoryForSnapshotRead(categoryId);
      order.verify(categoryQueryUseCase).getById(categoryId);
      ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
      order.verify(productRepository).save(productCaptor.capture());
      assertThat(productCaptor.getValue().getCategory()).isSameAs(category);
      assertThat(productCaptor.getValue().getThumbnailKey()).isEqualTo("final/thumb.png");
      then(eventPublisher).should().publishEvent(new ProductCreatedEvent(saved));
    }

    @Test
    @DisplayName("미분류 상품은 판매자 잠금만 획득한다")
    void create_uncategorized_locksSellerOnly() {
      UUID sellerId = UUID.randomUUID();
      ProductCreateCommand command =
          new ProductCreateCommand(sellerId, "상품", null, null, null, null, null);
      given(productRepository.save(any(Product.class)))
          .willReturn(ProductFixture.persisted(UUID.randomUUID(), sellerId));

      commandTransaction.create(command, null, null);

      then(referenceLock).should().lockSellerForSnapshotRead(sellerId);
      then(referenceLock).should(never()).lockCategoryForSnapshotRead(any());
      then(categoryQueryUseCase).shouldHaveNoInteractions();
    }
  }

  @Nested
  @DisplayName("수정")
  class Update {

    @Test
    @DisplayName("잠금 안에서 소유권과 카테고리를 재검증하고 수정 이벤트를 발행한다")
    void update_validOwner_revalidatesAndPublishes() {
      UUID productId = UUID.randomUUID();
      UUID sellerId = UUID.randomUUID();
      UUID categoryId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, sellerId);
      Category category = Category.create().name("전자기기").build();
      ProductUpdateCommand command =
          new ProductUpdateCommand(
              productId, sellerId, "수정 상품", null, categoryId, 20_000L, null, null);
      given(productRepository.findByIdForUpdate(productId))
          .willReturn(Optional.of(product));
      given(categoryQueryUseCase.getById(categoryId)).willReturn(category);

      commandTransaction.update(command, "final/thumb.png", List.of("final/detail.png"));

      assertThat(product.getName()).isEqualTo("수정 상품");
      assertThat(product.getCategory()).isSameAs(category);
      InOrder order = inOrder(referenceLock, productRepository, categoryQueryUseCase);
      order.verify(referenceLock).lockSellerForSnapshotRead(sellerId);
      order.verify(referenceLock).lockCategoryForSnapshotRead(categoryId);
      order.verify(productRepository).findByIdForUpdate(productId);
      order.verify(categoryQueryUseCase).getById(categoryId);
      then(eventPublisher).should().publishEvent(new ProductUpdatedEvent(product));
    }

    @Test
    @DisplayName("승격 뒤 상품이 사라졌으면 쓰기 트랜잭션에서 다시 거절한다")
    void update_productMissingAfterPromotion_rejects() {
      UUID productId = UUID.randomUUID();
      UUID sellerId = UUID.randomUUID();
      ProductUpdateCommand command =
          new ProductUpdateCommand(
              productId, sellerId, "수정", null, null, null, null, null);
      given(productRepository.findByIdForUpdate(productId))
          .willReturn(Optional.empty());

      assertThatThrownBy(() -> commandTransaction.update(command, null, null))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_FOUND);

      then(eventPublisher).shouldHaveNoInteractions();
    }
  }

  @Nested
  @DisplayName("삭제")
  class Delete {

    @Test
    @DisplayName("소유 상품을 잠그고 삭제 순번과 시각을 이벤트로 발행한다")
    void delete_validOwner_deletesAndPublishes() {
      UUID productId = UUID.randomUUID();
      UUID sellerId = UUID.randomUUID();
      Product product = ProductFixture.persisted(productId, sellerId);
      given(productRepository.findByIdForUpdate(productId))
          .willReturn(Optional.of(product));

      commandTransaction.delete(productId, sellerId);

      then(productRepository).should().delete(product);
      ArgumentCaptor<ProductDeletedEvent> eventCaptor =
          ArgumentCaptor.forClass(ProductDeletedEvent.class);
      then(eventPublisher).should().publishEvent(eventCaptor.capture());
      assertThat(eventCaptor.getValue().productId()).isEqualTo(productId);
      assertThat(eventCaptor.getValue().aggregateSequence()).isEqualTo(2L);
      assertThat(eventCaptor.getValue().deletedAt()).isNotNull();
    }
  }
}
