package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchProjectionRefreshTargetRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 검색 투영 갱신 배치")
class ProductSearchProjectionRefreshBatchProcessorTest {

  @Mock private ProductSearchProjectionRefreshTargetRepository refreshTargetRepository;
  @Mock private ProductRepository productRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private ProductSearchProjectionRefreshBatchProcessor processor;

  @BeforeEach
  void setUp() {
    processor =
        new ProductSearchProjectionRefreshBatchProcessor(
            refreshTargetRepository, productRepository, eventPublisher, 2);
  }

  @Test
  @DisplayName("대상이 없으면 상품을 잠그거나 이벤트를 만들지 않는다")
  void processNextBatch_empty_returnsWithoutWork() {
    given(refreshTargetRepository.findNextProductIdsForUpdate(2))
        .willReturn(List.of());

    int processed = processor.processNextBatch();

    assertThat(processed).isZero();
    then(productRepository).shouldHaveNoInteractions();
    then(eventPublisher).shouldHaveNoInteractions();
    then(refreshTargetRepository)
        .shouldHaveNoMoreInteractions();
  }

  @Test
  @DisplayName("제한된 대상만 잠그고 살아있는 상품의 순번과 outbox 이벤트를 갱신한다")
  void processNextBatch_targets_refreshesAliveProductsAndDeletesClaimedTargets() {
    UUID firstId = UUID.randomUUID();
    UUID deletedId = UUID.randomUUID();
    List<UUID> targetIds = List.of(firstId, deletedId);
    Product first = product(firstId);
    given(refreshTargetRepository.findNextProductIdsForUpdate(2))
        .willReturn(targetIds);
    given(productRepository.findAllByIdForUpdate(targetIds))
        .willReturn(List.of(first));

    int processed = processor.processNextBatch();

    assertThat(processed).isEqualTo(2);
    assertThat(first.currentSearchSnapshotSequence()).isEqualTo(2L);
    ArgumentCaptor<ProductUpdatedEvent> eventCaptor =
        ArgumentCaptor.forClass(ProductUpdatedEvent.class);
    then(eventPublisher).should().publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().product()).isSameAs(first);
    InOrder order = inOrder(productRepository, eventPublisher, refreshTargetRepository);
    order.verify(productRepository).findAllByIdForUpdate(targetIds);
    order.verify(eventPublisher).publishEvent(new ProductUpdatedEvent(first));
    order.verify(refreshTargetRepository).deleteAllByProductIds(targetIds);
  }

  @Test
  @DisplayName("batch size는 양수여야 한다")
  void constructor_nonPositiveBatchSize_rejects() {
    assertThatThrownBy(
            () ->
                new ProductSearchProjectionRefreshBatchProcessor(
                    refreshTargetRepository,
                    productRepository,
                    eventPublisher,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Product product(UUID id) {
    Product product =
        Product.create()
            .sellerId(UUID.randomUUID())
            .name("검색 투영 상품")
            .price(10_000L)
            .build();
    ReflectionTestUtils.setField(product, "id", id);
    return product;
  }
}
