package com.openat.search.product.presentation.controller;

import com.openat.search.product.application.service.ProductRecommendationService;
import com.openat.search.product.presentation.dto.ProductRecommendationRequest;
import com.openat.search.product.presentation.dto.ProductRecommendationResponse;
import com.openat.search.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/searchs")
@RequiredArgsConstructor
@Tag(name = "Search Product", description = "검색 상품 API")
public class ProductRecommendationController {

  private final ProductRecommendationService productRecommendationService;

  @Operation(
      summary = "가중 상품 임베딩 기반 상품 추천",
      description =
          "각 상품의 임베딩 벡터에 score 가중치를 곱해 합산한 뒤 상품 인덱스에서 유사한 상품을 검색합니다. buy=T로 표시된 상품은 추천 결과에서 제외됩니다.")
  @ApiResponse(responseCode = "200", description = "상품 추천 성공")
  @ApiErrorResponses
  @PostMapping("/recommand")
  public ResponseEntity<List<ProductRecommendationResponse>> recommend(
      @Valid @RequestBody ProductRecommendationRequest request) {
    return ResponseEntity.ok(productRecommendationService.recommend(request));
  }
}
