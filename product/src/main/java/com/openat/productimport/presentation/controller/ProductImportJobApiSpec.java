package com.openat.productimport.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.productimport.presentation.dto.ProductImportItemResponse;
import com.openat.productimport.presentation.dto.ProductImportJobResponse;
import com.openat.productimport.presentation.dto.ProductImportStartRequest;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

@Tag(name = "ProductImport", description = "CSV와 이미지 묶음을 이용한 상품 배치 등록 API")
public interface ProductImportJobApiSpec {

  @Operation(
      summary = "상품 가져오기 작업 시작",
      description = "허용된 로컬 폴더 또는 S3 prefix의 products.csv와 images를 비동기로 처리합니다.")
  @ApiResponse(
      responseCode = "202",
      description = "작업 접수",
      headers = @Header(name = "Location", description = "작업 상태 조회 URI"))
  @ApiErrorResponses
  ResponseEntity<ProductImportJobResponse> start(UUID sellerId, ProductImportStartRequest request);

  @Operation(summary = "상품 가져오기 작업 상태 조회")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<ProductImportJobResponse> getJob(UUID sellerId, UUID jobId);

  @Operation(summary = "상품 가져오기 행별 결과 조회")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<PageResponse<ProductImportItemResponse>> getItems(
      UUID sellerId, UUID jobId, Pageable pageable);
}
