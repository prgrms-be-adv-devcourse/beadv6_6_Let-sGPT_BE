package com.openat.product.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.exception.BusinessException;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.product.application.dto.ProductInfo;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.product.presentation.dto.ProductUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("상품 컨트롤러")
class ProductControllerTest {

  private static final String SELLER_ID_HEADER = "X-Seller-Id";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProductCommandUseCase productCommandUseCase;
  @MockitoBean private ProductQueryUseCase productQueryUseCase;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Nested
  @DisplayName("상품 등록")
  class Create {

    @Test
    @DisplayName("정상 요청이면 201과 Location 헤더를 반환한다")
    void create_validRequest_returns201WithLocation() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID createdId = UUID.randomUUID();
      given(productCommandUseCase.create(any())).willReturn(createdId);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(validRequest())))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", endsWith("/api/v1/products/" + createdId)));
    }

    @Test
    @DisplayName("판매자 식별 헤더가 없으면 401 UNAUTHENTICATED를 반환한다")
    void create_missingSellerHeader_returns401() throws Exception {
      // given
      ProductCreateRequest request = validRequest();

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
      then(productCommandUseCase).should(never()).create(any());
    }

    @Test
    @DisplayName("상품명이 비어 있으면 400 INVALID_INPUT을 반환한다")
    void create_blankName_returns400() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      ProductCreateRequest request = new ProductCreateRequest("  ", "설명", null, 1_000L, null, null);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(productCommandUseCase).should(never()).create(any());
    }

    @Test
    @DisplayName("판매가가 음수면 400 INVALID_INPUT을 반환한다")
    void create_negativePrice_returns400() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      ProductCreateRequest request =
          new ProductCreateRequest("한정판 스니커즈", "설명", null, -1L, null, null);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(productCommandUseCase).should(never()).create(any());
    }
  }

  @Nested
  @DisplayName("상품 단건 조회")
  class GetSingle {

    @Test
    @DisplayName("존재하는 상품이면 200과 상품 정보를 반환한다")
    void getProduct_existing_returns200() throws Exception {
      // given
      ProductInfo info = sampleInfo();
      given(productQueryUseCase.getById(info.id())).willReturn(info);

      // when & then
      mockMvc
          .perform(get("/api/v1/products/{id}", info.id()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(info.id().toString()))
          .andExpect(jsonPath("$.name").value("한정판 스니커즈"))
          .andExpect(jsonPath("$.sellerName").value("오픈앳 스튜디오"));
    }

    @Test
    @DisplayName("없는 상품이면 404 PRODUCT_NOT_FOUND를 반환한다")
    void getProduct_notFound_returns404() throws Exception {
      // given
      UUID missingId = UUID.randomUUID();
      given(productQueryUseCase.getById(missingId))
          .willThrow(new BusinessException(ProductErrorCode.NOT_FOUND));

      // when & then
      mockMvc
          .perform(get("/api/v1/products/{id}", missingId))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"));
    }
  }

  @Nested
  @DisplayName("상품 목록 조회")
  class GetList {

    @Test
    @DisplayName("목록을 페이징해 200으로 반환한다")
    void searchProducts_returns200() throws Exception {
      // given
      ProductInfo info = sampleInfo();
      given(
              productQueryUseCase.searchProducts(
                  any(ProductSearchCondition.class), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.of(info), PageRequest.of(0, 10), 1));

      // when & then
      mockMvc
          .perform(get("/api/v1/products").param("page", "0").param("size", "10"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value(info.id().toString()))
          .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("검색 파라미터가 조건으로 바인딩되어 유스케이스에 전달된다")
    void searchProducts_withFilters_bindsCondition() throws Exception {
      // given
      UUID categoryId = UUID.randomUUID();
      String keyword = "스니커즈";
      given(
              productQueryUseCase.searchProducts(
                  any(ProductSearchCondition.class), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.<ProductInfo>of(), PageRequest.of(0, 10), 0));

      // when
      mockMvc
          .perform(
              get("/api/v1/products")
                  .param("categoryId", categoryId.toString())
                  .param("keyword", keyword))
          .andExpect(status().isOk());

      // then
      ArgumentCaptor<ProductSearchCondition> conditionCaptor =
          ArgumentCaptor.forClass(ProductSearchCondition.class);
      then(productQueryUseCase)
          .should()
          .searchProducts(conditionCaptor.capture(), any(Pageable.class));
      assertThat(conditionCaptor.getValue().categoryId()).isEqualTo(categoryId);
      assertThat(conditionCaptor.getValue().keyword()).isEqualTo(keyword);
    }

    @Test
    @DisplayName("본인 상품 조회는 헤더의 sellerId를 검색 조건에 바인딩한다")
    void searchMyProducts_bindsSellerId() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      given(
              productQueryUseCase.searchProducts(
                  any(ProductSearchCondition.class), any(Pageable.class)))
          .willReturn(new PageImpl<>(List.<ProductInfo>of(), PageRequest.of(0, 10), 0));

      // when
      mockMvc
          .perform(get("/api/v1/products/me").header(SELLER_ID_HEADER, sellerId))
          .andExpect(status().isOk());

      // then
      ArgumentCaptor<ProductSearchCondition> conditionCaptor =
          ArgumentCaptor.forClass(ProductSearchCondition.class);
      then(productQueryUseCase)
          .should()
          .searchProducts(conditionCaptor.capture(), any(Pageable.class));
      assertThat(conditionCaptor.getValue().sellerId()).isEqualTo(sellerId);
    }
  }

  @Nested
  @DisplayName("상품 수정")
  class Update {

    @Test
    @DisplayName("정상 요청이면 204를 반환한다")
    void update_validRequest_returns204() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();

      // when & then
      mockMvc
          .perform(
              patch("/api/v1/products/{id}", productId)
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(updateRequest())))
          .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("소유자가 아니면 403 PRODUCT_NOT_OWNER를 반환한다")
    void update_notOwner_returns403() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      willThrow(new BusinessException(ProductErrorCode.NOT_OWNER))
          .given(productCommandUseCase)
          .update(any());

      // when & then
      mockMvc
          .perform(
              patch("/api/v1/products/{id}", productId)
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(updateRequest())))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value("PRODUCT_NOT_OWNER"));
    }

    @Test
    @DisplayName("상품명이 비어 있으면 400 INVALID_INPUT을 반환한다")
    void update_blankName_returns400() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      ProductUpdateRequest request = new ProductUpdateRequest("  ", "설명", null, 1_000L, null, null);

      // when & then
      mockMvc
          .perform(
              patch("/api/v1/products/{id}", productId)
                  .header(SELLER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(productCommandUseCase).should(never()).update(any());
    }
  }

  @Nested
  @DisplayName("상품 삭제")
  class Delete {

    @Test
    @DisplayName("정상 요청이면 204를 반환한다")
    void delete_returns204() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();

      // when & then
      mockMvc
          .perform(delete("/api/v1/products/{id}", productId).header(SELLER_ID_HEADER, sellerId))
          .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("소유자가 아니면 403 PRODUCT_NOT_OWNER를 반환한다")
    void delete_notOwner_returns403() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID productId = UUID.randomUUID();
      willThrow(new BusinessException(ProductErrorCode.NOT_OWNER))
          .given(productCommandUseCase)
          .delete(any(), any());

      // when & then
      mockMvc
          .perform(delete("/api/v1/products/{id}", productId).header(SELLER_ID_HEADER, sellerId))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value("PRODUCT_NOT_OWNER"));
    }
  }

  private ProductCreateRequest validRequest() {
    return new ProductCreateRequest("한정판 스니커즈", "설명", null, 219_000L, null, null);
  }

  private ProductUpdateRequest updateRequest() {
    return new ProductUpdateRequest("수정된 상품", "수정 설명", null, 150_000L, null, null);
  }

  private ProductInfo sampleInfo() {
    return new ProductInfo(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "오픈앳 스튜디오",
        "한정판 스니커즈",
        "설명",
        null,
        null,
        219_000L,
        null,
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
