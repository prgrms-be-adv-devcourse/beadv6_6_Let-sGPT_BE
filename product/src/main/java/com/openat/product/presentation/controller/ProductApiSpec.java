package com.openat.product.presentation.controller;

import com.openat.common.response.ApiResponse;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.product.presentation.dto.ProductCreateResponse;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@Tag(name = "Product", description = "상품 API")
public interface ProductApiSpec {

  @Operation(
      summary = "상품 등록",
      description = "판매자가 신규 상품을 등록한다.")
  @ApiErrorResponses
  ApiResponse<ProductCreateResponse> create(UUID sellerId, ProductCreateRequest request);
}
