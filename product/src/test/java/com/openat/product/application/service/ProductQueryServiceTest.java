package com.openat.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductInfo;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.product.fixture.ProductFixture;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 조회 서비스")
class ProductQueryServiceTest {

  @InjectMocks private ProductQueryService productQueryService;
  @Mock private ProductRepository productRepository;

  @Test
  @DisplayName("존재하는 상품을 조회하면 상품 정보를 반환한다")
  void getById_existing_returnsInfo() {
    // given
    UUID id = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    Product product = ProductFixture.persisted(id, sellerId);
    given(productRepository.findById(id)).willReturn(Optional.of(product));

    // when
    ProductInfo info = productQueryService.getById(id);

    // then
    assertThat(info.id()).isEqualTo(id);
    assertThat(info.sellerId()).isEqualTo(sellerId);
    assertThat(info.name()).isEqualTo("기본 굿즈");
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
    ProductSearchCondition condition = new ProductSearchCondition(null, null);
    Pageable pageable = PageRequest.of(0, 10);
    given(productRepository.search(condition, pageable))
        .willReturn(new PageImpl<>(List.of(product), pageable, 1));

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
}
