package com.openat.search.product.presentation.controller;

import com.openat.search.product.application.dto.ReIndexTestResult;
import com.openat.search.product.application.service.ReIndexTestService;
import com.openat.search.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/searchs")
@Tag(name = "Search Product", description = "검색 상품 API")
public class ReIndexTestController {

  private final ReIndexTestService reIndexTestService;

  @Operation(
      summary = "검색 상품 재색인 테스트 실행(GET)",
      description = "마지막 색인 시각 이후 변경된 상품을 조회하여 Elasticsearch에 다시 색인하고 실행 결과를 반환합니다.")
  @ApiResponse(responseCode = "200", description = "검색 상품 재색인 테스트 실행 성공")
  @ApiErrorResponses
  @GetMapping("/reIndexTest")
  public ResponseEntity<ReIndexTestResult> reIndexTestGet() {
    return ResponseEntity.ok(reIndexTestService.reIndexTest());
  }

  @Operation(
      summary = "검색 상품 재색인 테스트 실행(POST)",
      description = "마지막 색인 시각 이후 변경된 상품을 조회하여 Elasticsearch에 다시 색인하고 실행 결과를 반환합니다.")
  @ApiResponse(responseCode = "200", description = "검색 상품 재색인 테스트 실행 성공")
  @ApiErrorResponses
  @PostMapping("/reIndexTest")
  public ResponseEntity<ReIndexTestResult> reIndexTestPost() {
    return ResponseEntity.ok(reIndexTestService.reIndexTest());
  }
}
