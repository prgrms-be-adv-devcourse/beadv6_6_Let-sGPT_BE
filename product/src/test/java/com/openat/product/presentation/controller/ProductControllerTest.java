package com.openat.product.presentation.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.config.WebConfig;
import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.presentation.dto.ProductCreateRequest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("상품 컨트롤러")
class ProductControllerTest {

  private static final String USER_ID_HEADER = "X-User-Id";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProductCommandUseCase productCommandUseCase;
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
      ProductCreateRequest request = validRequest();
      given(productCommandUseCase.create(any(ProductCreateCommand.class))).willReturn(createdId);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .header(USER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", endsWith("/api/v1/products/" + createdId)));
    }

    @Test
    @DisplayName("상품명이 비어 있으면 400 INVALID_INPUT을 반환한다")
    void create_blankName_returns400() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      ProductCreateRequest request = new ProductCreateRequest("  ", "설명", null, 1_000L, null);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .header(USER_ID_HEADER, sellerId)
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
      ProductCreateRequest request = new ProductCreateRequest("한정판 스니커즈", "설명", null, -1L, null);

      // when & then
      mockMvc
          .perform(
              post("/api/v1/products")
                  .header(USER_ID_HEADER, sellerId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
      then(productCommandUseCase).should(never()).create(any());
    }
  }

  private ProductCreateRequest validRequest() {
    return new ProductCreateRequest("한정판 스니커즈", "설명", null, 219_000L, null);
  }
}
