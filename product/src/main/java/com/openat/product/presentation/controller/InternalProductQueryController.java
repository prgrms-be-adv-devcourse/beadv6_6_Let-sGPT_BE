package com.openat.product.presentation.controller;

import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.presentation.dto.ProductChangeResponse;
import com.openat.support.web.InternalApi;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products")
@InternalApi
@RequiredArgsConstructor
public class InternalProductQueryController {

  private final ProductQueryUseCase productQueryUseCase;

  @GetMapping("/changes")
  public ResponseEntity<List<ProductChangeResponse>> searchChanges(
      @RequestParam Instant changedAfter) {
    List<ProductChangeResponse> changes =
        productQueryUseCase.searchChanges(changedAfter).stream()
            .map(ProductChangeResponse::from)
            .toList();
    return ResponseEntity.ok(changes);
  }
}
