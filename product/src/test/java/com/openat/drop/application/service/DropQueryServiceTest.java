package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropInfo;
import com.openat.drop.application.dto.DropSnapshotInfo;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.DropSearchCondition;
import com.openat.product.domain.model.Product;
import com.openat.product.fixture.ProductFixture;
import com.openat.seller.application.usecase.SellerStoreQueryUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 조회 서비스")
class DropQueryServiceTest {

  @InjectMocks private DropQueryService dropQueryService;
  @Mock private DropRepository dropRepository;
  @Mock private DropCacheRepository dropCacheRepository;
  @Mock private SellerStoreQueryUseCase sellerStoreQueryUseCase;

  @Test
  @DisplayName("오픈 구간 드롭은 캐시 잔여로 OPEN 상태와 잔여 수량을 채워 반환한다")
  void getDrop_live_returnsOpenWithCachedRemaining() {
    // given
    UUID dropId = UUID.randomUUID();
    Drop drop = liveDrop(dropId);
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));
    given(dropCacheRepository.findRemaining(any())).willReturn(Map.of(dropId, 37L));
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of());

    // when
    DropInfo info = dropQueryService.getDrop(dropId);

    // then
    assertThat(info.id()).isEqualTo(dropId);
    assertThat(info.status()).isEqualTo(DropStatus.OPEN);
    assertThat(info.remainingQuantity()).isEqualTo(37);
  }

  @Test
  @DisplayName("없는 드롭을 조회하면 NOT_FOUND 예외를 던진다")
  void getDrop_notFound_throwsException() {
    // given
    UUID missingId = UUID.randomUUID();
    given(dropRepository.findById(missingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> dropQueryService.getDrop(missingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("오픈 전 드롭이 캐시에 없으면 잔여를 총 수량으로 채우고 REGISTERED로 반환한다")
  void getDrop_beforeOpenCacheMiss_usesTotalQuantity() {
    // given
    UUID dropId = UUID.randomUUID();
    Drop drop = dropOf(dropId, Instant.now().plusSeconds(3600), null);
    given(dropRepository.findById(dropId)).willReturn(Optional.of(drop));
    given(dropCacheRepository.findRemaining(any())).willReturn(Map.of());
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of());

    // when
    DropInfo info = dropQueryService.getDrop(dropId);

    // then
    assertThat(info.status()).isEqualTo(DropStatus.REGISTERED);
    assertThat(info.remainingQuantity()).isEqualTo(100);
  }

  @Test
  @DisplayName("목록은 페이징되며 잔여가 0이면 SOLD_OUT으로 파생된다")
  void searchDrops_soldOut_mapsSoldOut() {
    // given
    UUID dropId = UUID.randomUUID();
    Drop drop = liveDrop(dropId);
    Pageable pageable = PageRequest.of(0, 10);
    given(
            dropRepository.search(
                any(DropSearchCondition.class), any(Instant.class), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of(drop), pageable, 1));
    given(dropCacheRepository.findRemaining(any())).willReturn(Map.of(dropId, 0L));
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of());

    // when
    Page<DropInfo> result =
        dropQueryService.searchDrops(new DropSearchCondition(null, null, null, null), pageable);

    // then
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent().get(0).status()).isEqualTo(DropStatus.SOLD_OUT);
    assertThat(result.getContent().get(0).remainingQuantity()).isZero();
  }

  @Test
  @DisplayName("드롭 스냅샷은 productId·sellerId·드롭가를 반환한다")
  void getDropSnapshot_returnsProductSellerAndPrice() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    Drop drop = dropWithProduct(dropId, productId, sellerId, 219_000L);
    given(dropRepository.findWithProductById(dropId)).willReturn(Optional.of(drop));

    // when
    DropSnapshotInfo snapshot = dropQueryService.getDropSnapshot(dropId);

    // then
    assertThat(snapshot.productId()).isEqualTo(productId);
    assertThat(snapshot.sellerId()).isEqualTo(sellerId);
    assertThat(snapshot.unitPrice()).isEqualTo(219_000L);
  }

  @Test
  @DisplayName("없는 드롭의 스냅샷을 조회하면 NOT_FOUND 예외를 던진다")
  void getDropSnapshot_notFound_throwsException() {
    // given
    UUID missingId = UUID.randomUUID();
    given(dropRepository.findWithProductById(missingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> dropQueryService.getDropSnapshot(missingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.NOT_FOUND);
  }

  private Drop dropWithProduct(UUID dropId, UUID productId, UUID sellerId, long dropPrice) {
    Product product = ProductFixture.persisted(productId, sellerId);
    Drop drop =
        Drop.schedule()
            .product(product)
            .dropPrice(dropPrice)
            .totalQuantity(100)
            .openAt(Instant.now().minusSeconds(60))
            .closeAt(null)
            .build();
    ReflectionTestUtils.setField(drop, "id", dropId);
    return drop;
  }

  private Drop liveDrop(UUID dropId) {
    return dropOf(dropId, Instant.now().minusSeconds(60), null);
  }

  private Drop dropOf(UUID dropId, Instant openAt, Instant closeAt) {
    Product product = ProductFixture.persisted(UUID.randomUUID(), UUID.randomUUID());
    Drop drop =
        Drop.schedule()
            .product(product)
            .dropPrice(219_000L)
            .totalQuantity(100)
            .openAt(openAt)
            .closeAt(closeAt)
            .build();
    ReflectionTestUtils.setField(drop, "id", dropId);
    return drop;
  }
}
