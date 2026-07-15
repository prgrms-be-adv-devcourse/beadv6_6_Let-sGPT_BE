package com.openat.search.product.presentation.controller;

import com.openat.search.product.application.dto.ProductSearchSyncTestResponse;
import com.openat.search.product.application.service.ProductTopicProduceTestService;
import com.openat.search.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/searchs")
@Tag(name = "Search Product", description = "검색 상품 API")
public class ProductSearchSyncTestController {

  private final ProductTopicProduceTestService productTopicProduceTestService;

  @Operation(
      summary = "검색 상품 동기화 테스트 데이터 조회",
      description = "changedAfter 이후 발생한 상품 등록, 수정, 삭제 동기화 테스트 데이터를 생성하여 반환합니다.")
  @ApiResponse(responseCode = "200", description = "검색 상품 동기화 테스트 데이터 조회 성공")
  @ApiErrorResponses
  @GetMapping(value = "/search-sync-test", produces = MediaType.APPLICATION_JSON_VALUE)
  public List<ProductSearchSyncTestResponse> searchSyncTest(
      @Parameter(
              description = "이 시각 이후 변경된 상품을 조회하기 위한 ISO-8601 형식의 기준 시각",
              example = "2026-07-14T00:00:00.000Z")
          @RequestParam(defaultValue = "1970-01-01T00:00:00.000Z")
          String changedAfter) {
    return productTopicProduceTestService.searchSyncTestProducts(Instant.parse(changedAfter));
  }
}
