package com.openat.recommendation.presentation.controller;

import com.openat.recommendation.application.service.RecommendationResponse;
import com.openat.recommendation.application.service.RecommendationService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecommendationController {

  private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

  private final RecommendationService recommendationService;

  public RecommendationController(RecommendationService recommendationService) {
    this.recommendationService = recommendationService;
  }

  @GetMapping("/api/v1/recommendations")
  public ResponseEntity<RecommendationResponse> recommendations(
      @RequestParam(required = false) String productId) {
    try {
      UUID parsedProductId = productId == null ? null : UUID.fromString(productId);
      return ResponseEntity.ok(recommendationService.recommend(parsedProductId));
    } catch (Exception exception) {
      log.warn(
          "recommendation request failed, returning empty: productId={}", productId, exception);
      return ResponseEntity.ok(RecommendationResponse.empty());
    }
  }
}
