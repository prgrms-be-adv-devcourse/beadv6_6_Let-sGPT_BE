package com.openat.recommendation.presentation.controller;

import com.openat.recommendation.application.service.RecommendationResponse;
import com.openat.recommendation.application.service.RecommendationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecommendationController {

  private final RecommendationService recommendationService;

  public RecommendationController(RecommendationService recommendationService) {
    this.recommendationService = recommendationService;
  }

  // 형식 오류는 바인딩 단계에서 400으로 끝나고, 인프라 장애는 서비스가 폴백으로 흡수한다.
  @GetMapping("/api/v1/recommendations")
  public ResponseEntity<RecommendationResponse> recommendations(
      @RequestParam(required = false) UUID productId) {
    return ResponseEntity.ok(recommendationService.recommend(productId));
  }
}
