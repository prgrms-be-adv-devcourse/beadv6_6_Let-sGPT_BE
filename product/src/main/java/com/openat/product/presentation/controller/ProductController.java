package com.openat.product.presentation.controller;

import com.openat.common.response.ApiResponse;
import com.openat.product.application.usecase.ProductCommandUseCase;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.product.presentation.dto.ProductCreateResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

  private final ProductCommandUseCase productCommandUseCase;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ProductCreateResponse> create(
      // TODO: 회원 도메인/게이트웨이 인증 연동 전까지 테스트용 헤더로 sellerId 수신 -> 인증 컨텍스트 추출로 교체
      @RequestHeader("X-User-Id") UUID sellerId,
      @Valid @RequestBody ProductCreateRequest request) {
    UUID productId = productCommandUseCase.create(request.toCommand(sellerId));
    return ApiResponse.of(new ProductCreateResponse(productId), HttpStatus.CREATED);
  }
}
