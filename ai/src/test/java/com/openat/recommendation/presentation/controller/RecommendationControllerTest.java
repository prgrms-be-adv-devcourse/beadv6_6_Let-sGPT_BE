package com.openat.recommendation.presentation.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                new Section(
                    "추천", List.of(new Product(productId, "상품", "판매자", 1000L, "thumb")))));
    when(recommendationService.recommend(isNull())).thenReturn(response);

    mvc().perform(get("/api/v1/recommendations")).andExpect(status().isOk())
        .andExpect(jsonPath("$.sections", hasSize(1)));
  }

  @Test
  void recommendations_whenProductIdIsMalformed_returnsEmptyWithOkStatus() throws Exception {
    mvc().perform(get("/api/v1/recommendations").param("productId", "not-a-uuid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections", hasSize(0)));
  }

  @Test
  void recommendations_forDetailRequest_passesParsedProductIdToService() throws Exception {
    UUID productId = UUID.randomUUID();
    when(recommendationService.recommend(eq(productId)))
        .thenReturn(RecommendationResponse.empty());

    mvc().perform(get("/api/v1/recommendations").param("productId", productId.toString()))
        .andExpect(status().isOk());

    verify(recommendationService).recommend(eq(productId));
  }

  @Test
  void recommendations_whenServiceThrows_returnsEmptyWithOkStatus() throws Exception {
    when(recommendationService.recommend(any())).thenThrow(new RuntimeException("boom"));

    mvc().perform(get("/api/v1/recommendations")).andExpect(status().isOk())
        .andExpect(jsonPath("$.sections", hasSize(0)));
  }

  private MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new RecommendationController(recommendationService))
        .build();
  }
}
