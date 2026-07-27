package com.openat.recommendation.presentation.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.auth.UserContextFilter;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.recommendation.application.service.RecommendationResponse;
import com.openat.recommendation.application.service.RecommendationResponse.Product;
import com.openat.recommendation.application.service.RecommendationResponse.Section;
import com.openat.recommendation.application.service.RecommendationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

  @Mock RecommendationService recommendationService;

  @Test
  void recommendations_forHomeRequest_returnsServiceResult() throws Exception {
    UUID productId = UUID.randomUUID();
    RecommendationResponse response =
        new RecommendationResponse(
            List.of(
                new Section("추천", List.of(new Product(productId, "상품", "판매자", 1000L, "thumb")))));
    when(recommendationService.recommend(isNull())).thenReturn(response);

    mvc()
        .perform(get("/api/v1/recommendations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections", hasSize(1)));
  }

  @Test
  void recommendations_whenProductIdIsMalformed_returnsBadRequest() throws Exception {
    // 앱 전역 GlobalExceptionHandler의 catch-all(Exception→500)까지 함께 등록해, 형식 오류가
    // 500이 아니라 400으로 응답되는지(우선순위 높은 타입 불일치 핸들러가 이기는지) 확인한다.
    MockMvcBuilders.standaloneSetup(new RecommendationController(recommendationService))
        .setControllerAdvice(new RecommendationExceptionHandler(), new GlobalExceptionHandler())
        .build()
        .perform(get("/api/v1/recommendations").param("productId", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
  }

  @Test
  void recommendations_forDetailRequest_passesParsedProductIdToService() throws Exception {
    UUID productId = UUID.randomUUID();
    when(recommendationService.recommend(eq(productId))).thenReturn(RecommendationResponse.empty());

    mvc()
        .perform(get("/api/v1/recommendations").param("productId", productId.toString()))
        .andExpect(status().isOk());

    verify(recommendationService).recommend(eq(productId));
  }

  @Test
  void recommendations_whenUserIdHeaderIsMalformed_returnsOkNotError() throws Exception {
    when(recommendationService.recommend(isNull())).thenReturn(RecommendationResponse.empty());

    MockMvcBuilders.standaloneSetup(new RecommendationController(recommendationService))
        .addFilters(new UserContextFilter())
        .build()
        .perform(get("/api/v1/recommendations").header("X-User-Id", "not-a-uuid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections", hasSize(0)));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new RecommendationController(recommendationService))
        .build();
  }
}
