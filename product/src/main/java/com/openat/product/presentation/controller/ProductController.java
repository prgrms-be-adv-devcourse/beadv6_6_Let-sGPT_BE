package com.openat.product.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.common.web.Locations;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.product.presentation.dto.ProductResponse;
import com.openat.product.presentation.dto.ProductSearchRequest;
import com.openat.product.presentation.dto.ProductUpdateRequest;
import com.openat.support.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController implements ProductApiSpec {

  private final ProductCommandUseCase productCommandUseCase;
  private final ProductQueryUseCase productQueryUseCase;

  @Override
  @PostMapping
  public ResponseEntity<Void> create(
      @CurrentUser UUID sellerId, @Valid @RequestBody ProductCreateRequest request) {
    UUID productId = productCommandUseCase.create(request.toCommand(sellerId));
    return ResponseEntity.created(Locations.fromCurrentRequest(productId)).build();
  }

  @Override
  @GetMapping("/{id}")
  public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID id) {
    return ResponseEntity.ok(ProductResponse.from(productQueryUseCase.getById(id)));
  }

  @Override
  @GetMapping
  public ResponseEntity<PageResponse<ProductResponse>> searchProducts(
      @ModelAttribute ProductSearchRequest request, Pageable pageable) {
    Page<ProductResponse> page =
        productQueryUseCase
            .searchProducts(request.toCondition(), pageable)
            .map(ProductResponse::from);
    return ResponseEntity.ok(PageResponse.of(page));
  }

  @Override
  @PatchMapping("/{id}")
  public ResponseEntity<Void> update(
      @CurrentUser UUID sellerId,
      @PathVariable UUID id,
      @Valid @RequestBody ProductUpdateRequest request) {
    productCommandUseCase.update(request.toCommand(id, sellerId));
    return ResponseEntity.noContent().build();
  }

  @Override
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@CurrentUser UUID sellerId, @PathVariable UUID id) {
    productCommandUseCase.delete(id, sellerId);
    return ResponseEntity.noContent().build();
  }
}
