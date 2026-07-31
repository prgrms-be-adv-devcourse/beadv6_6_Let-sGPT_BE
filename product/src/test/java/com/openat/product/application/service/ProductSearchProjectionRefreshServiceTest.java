package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.openat.category.domain.event.CategoryDeletingEvent;
import com.openat.category.domain.event.CategoryUpdatedEvent;
import com.openat.category.domain.model.Category;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.event.SellerStoreProjectionChangedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 검색 투영 갱신 서비스")
class ProductSearchProjectionRefreshServiceTest {

  @InjectMocks private ProductSearchProjectionRefreshService service;
  @Mock private ProductRepository productRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  @Test
  @DisplayName("스토어 이름이 바뀌면 판매자의 모든 상품 버전을 올려 갱신 이벤트를 발행한다")
  void onSellerStoreProjectionChanged_refreshesSellerProducts() {
    UUID sellerId = UUID.randomUUID();
    Product first = product(sellerId, null);
    Product second = product(sellerId, null);
    given(productRepository.findAllBySellerIdForUpdate(sellerId))
        .willReturn(List.of(first, second));

    service.onSellerStoreProjectionChanged(new SellerStoreProjectionChangedEvent(sellerId));

    assertThat(first.currentSearchSnapshotSequence()).isEqualTo(2L);
    assertThat(second.currentSearchSnapshotSequence()).isEqualTo(2L);
    ArgumentCaptor<ProductUpdatedEvent> eventCaptor =
        ArgumentCaptor.forClass(ProductUpdatedEvent.class);
    then(eventPublisher).should(times(2)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues())
        .extracting(ProductUpdatedEvent::product)
        .containsExactly(first, second);
  }

  @Test
  @DisplayName("카테고리 이름이 바뀌면 해당 상품의 검색 스냅샷을 갱신한다")
  void onCategoryUpdated_refreshesCategoryProducts() {
    UUID categoryId = UUID.randomUUID();
    Product product = product(UUID.randomUUID(), category(categoryId));
    given(productRepository.findAllByCategoryIdForUpdate(categoryId))
        .willReturn(List.of(product));

    service.onCategoryUpdated(new CategoryUpdatedEvent(categoryId));

    assertThat(product.currentSearchSnapshotSequence()).isEqualTo(2L);
    then(eventPublisher).should().publishEvent(new ProductUpdatedEvent(product));
  }

  @Test
  @DisplayName("카테고리 삭제 전 해당 상품의 참조를 제거하고 검색 스냅샷을 갱신한다")
  void onCategoryDeleting_removesCategoryAndRefreshesProducts() {
    UUID categoryId = UUID.randomUUID();
    Product product = product(UUID.randomUUID(), category(categoryId));
    given(productRepository.findAllByCategoryIdForUpdate(categoryId))
        .willReturn(List.of(product));

    service.onCategoryDeleting(new CategoryDeletingEvent(categoryId));

    assertThat(product.getCategory()).isNull();
    assertThat(product.currentSearchSnapshotSequence()).isEqualTo(2L);
    then(eventPublisher).should().publishEvent(new ProductUpdatedEvent(product));
  }

  private Product product(UUID sellerId, Category category) {
    return Product.create()
        .sellerId(sellerId)
        .name("검색 투영 상품")
        .category(category)
        .price(10_000L)
        .build();
  }

  private Category category(UUID categoryId) {
    Category category = Category.create().name("의류").build();
    ReflectionTestUtils.setField(category, "id", categoryId);
    return category;
  }
}
