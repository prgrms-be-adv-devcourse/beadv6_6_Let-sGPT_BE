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

  // productId를 UUID로 직접 바인딩한다. 값이 없으면(홈) null, 형식이 잘못되면 바인딩 단계에서
  // MethodArgumentTypeMismatchException → 400으로 끝난다(잘못된 클라이언트 입력). 인프라 장애는
  // RecommendationService가 폴백/빈 응답으로 흡수하므로 컨트롤러는 별도 예외 처리를 두지 않는다.
  @GetMapping("/api/v1/recommendations")
  public ResponseEntity<RecommendationResponse> recommendations(
      @RequestParam(required = false) UUID productId) {
    return ResponseEntity.ok(recommendationService.recommend(productId));
  }
}
