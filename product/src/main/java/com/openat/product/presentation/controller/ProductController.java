package com.openat.product.presentation.controller;

import com.openat.common.web.Locations;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.support.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController implements ProductApiSpec {

  private final ProductCommandUseCase productCommandUseCase;

  @PostMapping
  public ResponseEntity<Void> create(
      @CurrentUser UUID sellerId, @Valid @RequestBody ProductCreateRequest request) {
    UUID productId = productCommandUseCase.create(request.toCommand(sellerId));
    return ResponseEntity.created(Locations.fromCurrentRequest(productId)).build();
  }
}
