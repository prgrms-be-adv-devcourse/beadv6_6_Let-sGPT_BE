package com.openat.product.application.service;

import static org.mockito.BDDMockito.then;

import com.openat.category.domain.event.CategoryDeletingEvent;
import com.openat.category.domain.event.CategoryUpdatedEvent;
import com.openat.product.domain.event.SellerStoreProjectionChangedEvent;
import com.openat.product.domain.repository.ProductSearchProjectionRefreshTargetRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 검색 투영 갱신 대상 적재")
class ProductSearchProjectionRefreshServiceTest {

  @InjectMocks private ProductSearchProjectionRefreshService service;
  @Mock private ProductSearchProjectionRefreshTargetRepository refreshTargetRepository;

  @Test
  @DisplayName("스토어 이름이 바뀌면 해당 판매자의 상품을 갱신 대상으로 적재한다")
  void onSellerStoreProjectionChanged_enqueuesSellerProducts() {
    UUID sellerId = UUID.randomUUID();

    service.onSellerStoreProjectionChanged(
        new SellerStoreProjectionChangedEvent(sellerId));

    then(refreshTargetRepository).should().enqueueBySellerId(sellerId);
  }

  @Test
  @DisplayName("카테고리 이름이 바뀌면 해당 상품을 갱신 대상으로 적재한다")
  void onCategoryUpdated_enqueuesCategoryProducts() {
    UUID categoryId = UUID.randomUUID();

    service.onCategoryUpdated(new CategoryUpdatedEvent(categoryId));

    then(refreshTargetRepository).should().enqueueByCategoryId(categoryId);
  }

  @Test
  @DisplayName("카테고리 삭제 전에 영향 상품을 갱신 대상으로 적재한다")
  void onCategoryDeleting_enqueuesCategoryProductsBeforeDelete() {
    UUID categoryId = UUID.randomUUID();

    service.onCategoryDeleting(new CategoryDeletingEvent(categoryId));

    then(refreshTargetRepository).should().enqueueByCategoryId(categoryId);
  }
}
