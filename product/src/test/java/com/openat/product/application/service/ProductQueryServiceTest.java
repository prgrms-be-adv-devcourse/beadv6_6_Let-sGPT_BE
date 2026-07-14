package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductChangeInfo;
import com.openat.product.application.dto.ProductChangeOperation;
import com.openat.product.application.dto.ProductInfo;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.product.domain.repository.ProductTombstone;
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
@DisplayName("상품 조회 서비스")
class ProductQueryServiceTest {

  @InjectMocks private ProductQueryService productQueryService;
  @Mock private ProductRepository productRepository;
  @Mock private SellerStoreQueryUseCase sellerStoreQueryUseCase;

  @Test
  @DisplayName("존재하는 상품을 조회하면 상품 정보를 반환한다")
  void getById_existing_returnsInfo() {
    // given
    UUID id = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    Product product = ProductFixture.persisted(id, sellerId);
    given(productRepository.findById(id)).willReturn(Optional.of(product));
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of(sellerId, "오픈앳 스튜디오"));

    // when
    ProductInfo info = productQueryService.getById(id);

    // then
    assertThat(info.id()).isEqualTo(id);
    assertThat(info.sellerId()).isEqualTo(sellerId);
    assertThat(info.name()).isEqualTo("기본 굿즈");
    assertThat(info.sellerName()).isEqualTo("오픈앳 스튜디오");
  }

  @Test
  @DisplayName("없는 상품을 조회하면 NOT_FOUND 예외를 던진다")
  void getById_notFound_throwsException() {
    // given
    UUID missingId = UUID.randomUUID();
    given(productRepository.findById(missingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> productQueryService.getById(missingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("검색 조건으로 상품 목록을 페이징해 반환한다")
  void searchProducts_returnsPagedInfo() {
    // given
    UUID sellerId = UUID.randomUUID();
    Product product = ProductFixture.persisted(UUID.randomUUID(), sellerId);
    ProductSearchCondition condition = new ProductSearchCondition(null, null, null);
    Pageable pageable = PageRequest.of(0, 10);
    given(productRepository.search(condition, pageable))
        .willReturn(new PageImpl<>(List.of(product), pageable, 1));
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of(sellerId, "오픈앳 스튜디오"));

    // when
    Page<ProductInfo> result = productQueryService.searchProducts(condition, pageable);

    // then
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).sellerId()).isEqualTo(sellerId);
  }

  @Test
  @DisplayName("소유 상품을 조회하면 상품 엔티티를 반환한다")
  void getOwnedProduct_owned_returnsEntity() {
    // given
    UUID id = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    Product product = ProductFixture.persisted(id, sellerId);
    given(productRepository.findById(id)).willReturn(Optional.of(product));

    // when
    Product result = productQueryService.getOwnedProduct(id, sellerId);

    // then
    assertThat(result).isSameAs(product);
  }

  @Test
  @DisplayName("없는 상품을 소유 조회하면 NOT_FOUND 예외를 던진다")
  void getOwnedProduct_notFound_throwsException() {
    // given
    UUID missingId = UUID.randomUUID();
    given(productRepository.findById(missingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> productQueryService.getOwnedProduct(missingId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("소유자가 아니면 NOT_OWNER 예외를 던진다")
  void getOwnedProduct_notOwner_throwsException() {
    // given
    UUID id = UUID.randomUUID();
    Product product = ProductFixture.persisted(id, UUID.randomUUID());
    given(productRepository.findById(id)).willReturn(Optional.of(product));

    // when & then
    assertThatThrownBy(() -> productQueryService.getOwnedProduct(id, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.NOT_OWNER);
  }

  @Test
  @DisplayName("생성·수정·삭제를 operation으로 태깅하고 변경 시각 오름차순으로 병합한다")
  void searchChanges_tagsOperationsAndMergesSortedByChangedAt() {
    // given
    Instant changedAfter = Instant.parse("2026-06-01T00:00:00Z");
    UUID sellerId = UUID.randomUUID();
    Product inserted =
        changedProduct(
            sellerId,
            "새 상품",
            "new.jpg",
            Instant.parse("2026-06-02T00:00:00Z"),
            Instant.parse("2026-06-02T00:00:00Z"));
    Product updated =
        changedProduct(
            sellerId,
            "수정 상품",
            "upd.jpg",
            Instant.parse("2026-05-01T00:00:00Z"),
            Instant.parse("2026-06-03T00:00:00Z"));
    ProductTombstone tombstone =
        new ProductTombstone(UUID.randomUUID(), Instant.parse("2026-06-04T00:00:00Z"));
    given(productRepository.searchChangedAliveSince(changedAfter))
        .willReturn(List.of(inserted, updated));
    given(productRepository.searchTombstonesSince(changedAfter)).willReturn(List.of(tombstone));
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of(sellerId, "오픈앳 스튜디오"));

    // when
    List<ProductChangeInfo> changes = productQueryService.searchChanges(changedAfter);

    // then
    assertThat(changes)
        .extracting(ProductChangeInfo::operation)
        .containsExactly(
            ProductChangeOperation.INSERT,
            ProductChangeOperation.UPDATE,
            ProductChangeOperation.DELETE);
    assertThat(changes)
        .extracting(ProductChangeInfo::id)
        .containsExactly(inserted.getId(), updated.getId(), tombstone.id());
    assertThat(changes.get(0).thumbnailKey()).isEqualTo("new.jpg");
    assertThat(changes.get(0).sellerName()).isEqualTo("오픈앳 스튜디오");
  }

  @Test
  @DisplayName("삭제 항목은 id와 삭제 시각만 채우고 나머지 필드는 비운다")
  void searchChanges_deletion_fillsOnlyIdAndDeletedAt() {
    // given
    Instant changedAfter = Instant.parse("2026-06-01T00:00:00Z");
    ProductTombstone tombstone =
        new ProductTombstone(UUID.randomUUID(), Instant.parse("2026-06-04T00:00:00Z"));
    given(productRepository.searchChangedAliveSince(changedAfter)).willReturn(List.of());
    given(productRepository.searchTombstonesSince(changedAfter)).willReturn(List.of(tombstone));
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of());

    // when
    ProductChangeInfo deletion = productQueryService.searchChanges(changedAfter).get(0);

    // then
    assertThat(deletion.operation()).isEqualTo(ProductChangeOperation.DELETE);
    assertThat(deletion.id()).isEqualTo(tombstone.id());
    assertThat(deletion.deletedAt()).isEqualTo(tombstone.deletedAt());
    assertThat(deletion.name()).isNull();
    assertThat(deletion.thumbnailKey()).isNull();
    assertThat(deletion.createdAt()).isNull();
    assertThat(deletion.updatedAt()).isNull();
  }

  @Test
  @DisplayName("썸네일 키가 없으면 변경 피드의 썸네일 키도 null이다")
  void searchChanges_nullThumbnail_returnsNullKey() {
    // given
    Instant changedAfter = Instant.parse("2026-06-01T00:00:00Z");
    UUID sellerId = UUID.randomUUID();
    Product noThumbnail =
        changedProduct(
            sellerId,
            "썸네일 없음",
            null,
            Instant.parse("2026-06-02T00:00:00Z"),
            Instant.parse("2026-06-02T00:00:00Z"));
    given(productRepository.searchChangedAliveSince(changedAfter)).willReturn(List.of(noThumbnail));
    given(productRepository.searchTombstonesSince(changedAfter)).willReturn(List.of());
    given(sellerStoreQueryUseCase.findStoreNames(any())).willReturn(Map.of(sellerId, "오픈앳 스튜디오"));

    // when
    ProductChangeInfo change = productQueryService.searchChanges(changedAfter).get(0);

    // then
    assertThat(change.thumbnailKey()).isNull();
  }

  private Product changedProduct(
      UUID sellerId, String name, String thumbnailKey, Instant createdAt, Instant updatedAt) {
    Product product =
        Product.create()
            .sellerId(sellerId)
            .name(name)
            .price(10_000L)
            .thumbnailKey(thumbnailKey)
            .build();
    ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(product, "createdAt", createdAt);
    ReflectionTestUtils.setField(product, "updatedAt", updatedAt);
    return product;
  }
}
