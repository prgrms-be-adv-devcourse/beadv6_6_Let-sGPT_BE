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
@Tag(name = "Search Product", description = "Product search API")
public class ProductRecommendationController {

  private final ProductRecommendationService productRecommendationService;

  @Operation(
      summary = "Recommend products from weighted product embeddings",
      description =
          "Loads each product embedding, multiplies it by score, sums the vectors, and searches the products index. Products marked buy=T are excluded from the returned candidates.")
  @ApiResponse(responseCode = "200", description = "Product recommendation succeeded")
  @ApiErrorResponses
  @PostMapping("/recommand")
  public ResponseEntity<List<ProductRecommendationResponse>> recommend(
      @Valid @RequestBody ProductRecommendationRequest request) {
    return ResponseEntity.ok(productRecommendationService.recommend(request));
  }
}
