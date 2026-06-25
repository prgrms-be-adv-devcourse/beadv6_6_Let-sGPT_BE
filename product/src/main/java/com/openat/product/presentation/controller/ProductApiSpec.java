package com.openat.product.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.product.presentation.dto.ProductResponse;
import com.openat.product.presentation.dto.ProductSearchRequest;
import com.openat.product.presentation.dto.ProductUpdateRequest;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

@Tag(name = "Product", description = "상품 API")
public interface ProductApiSpec {

  @Operation(summary = "상품 등록", description = "판매자가 신규 상품을 등록한다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      headers = @Header(name = "Location", description = "생성된 리소스 URI"))
  @ApiErrorResponses
  ResponseEntity<Void> create(UUID sellerId, ProductCreateRequest request);

  @Operation(summary = "상품 단건 조회", description = "상품 id로 단건을 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<ProductResponse> getProduct(UUID id);

  @Operation(summary = "상품 목록 조회", description = "상품을 페이징·검색 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<PageResponse<ProductResponse>> searchProducts(
      @ParameterObject ProductSearchRequest request, Pageable pageable);

  @Operation(summary = "상품 수정", description = "판매자가 자신의 상품 정보를 수정한다.")
  @ApiResponse(responseCode = "204", description = "수정 성공")
  @ApiErrorResponses
  ResponseEntity<Void> update(UUID sellerId, UUID id, ProductUpdateRequest request);

  @Operation(summary = "상품 삭제", description = "판매자가 자신의 상품을 삭제한다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공")
  @ApiErrorResponses
  ResponseEntity<Void> delete(UUID sellerId, UUID id);
}
