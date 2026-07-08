package com.openat.search.product.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.search.product.application.service.AiImageService;
import com.openat.search.product.application.service.ProductSearchService;
import com.openat.search.product.application.service.ProductTopicProduceTestService;
import com.openat.search.product.infrastructure.batch.ProductDbToEsJobConfig;
import com.openat.search.product.presentation.dto.AiImageAnalyzeResponse;
import com.openat.search.product.presentation.dto.DbToEsResponse;
import com.openat.search.product.presentation.dto.ProductResponse;
import com.openat.search.product.presentation.dto.ProductSearchApiRequest;
import com.openat.search.product.presentation.dto.TopicProduceTestResponse;
import com.openat.search.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/searchs")
@RequiredArgsConstructor
@Tag(name = "Search Product", description = "검색 상품 API")
public class ProductController {

  private final JobLauncher jobLauncher;
  private final ProductSearchService productSearchService;
  private final ProductTopicProduceTestService productTopicProduceTestService;
  private final AiImageService aiImageService;

  @Qualifier(ProductDbToEsJobConfig.PRODUCT_DB_TO_ES_JOB)
  private final Job productDbToEsJob;

  @Operation(
      summary = "상품 DB를 Elasticsearch로 색인",
      description = "상품 DB 데이터를 Spring Batch chunk 단위로 읽어서 Elasticsearch products 인덱스에 저장합니다.")
  @ApiResponse(responseCode = "200", description = "DB to ES 배치 실행 성공")
  @ApiErrorResponses
  @GetMapping("/dbToEs")
  public ResponseEntity<DbToEsResponse> dbToEs() {
    try {
      JobExecution execution = jobLauncher.run(productDbToEsJob, newJobParameters());
      return ResponseEntity.ok(
          new DbToEsResponse(
              execution.getId(),
              execution.getStatus().name(),
              execution.getExitStatus().getExitCode()));
    } catch (JobExecutionAlreadyRunningException
        | JobRestartException
        | JobInstanceAlreadyCompleteException
        | InvalidJobParametersException e) {
      return ResponseEntity.internalServerError()
          .body(new DbToEsResponse(null, BatchStatus.FAILED.name(), e.getMessage()));
    }
  }

  @Operation(
      summary = "상품 검색",
      description =
          "query가 있으면 자연어를 embedding 벡터로 변환해 dense_vector 유사도 검색을 하고, categoryName/가격 범위 필터와 score를 함께 제공합니다. query가 없으면 기존 조건 검색을 수행합니다.")
  @ApiResponse(responseCode = "200", description = "상품 검색 성공")
  @ApiErrorResponses
  @PostMapping("/search")
  public ResponseEntity<PageResponse<ProductResponse>> searchProducts(
      @RequestBody ProductSearchApiRequest request) {
    ProductSearchApiRequest safeRequest =
        request == null ? new ProductSearchApiRequest(null, null, null, null, null, null) : request;

    Page<ProductResponse> products =
        productSearchService
            .search(
                safeRequest.query(),
                safeRequest.categoryName(),
                safeRequest.startPrice(),
                safeRequest.endPrice(),
                safeRequest.page(),
                safeRequest.size())
            .map(ProductResponse::from);

    return ResponseEntity.ok(PageResponse.of(products));
  }

  @Operation(summary = "이미지 분석", description = "업로드한 이미지를 AI가 설명합니다.")
  @ApiResponse(responseCode = "200", description = "이미지 분석 성공")
  @ApiErrorResponses
  @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public AiImageAnalyzeResponse analyze(
      @Parameter(description = "분석할 이미지") @RequestPart("image") MultipartFile image,
      @Parameter(description = "선택 프롬프트") @RequestParam(required = false) String prompt) {
    return aiImageService.analyze(image, prompt);
  }

  @Operation(
      summary = "상품 Kafka 토픽 테스트 발행",
      description =
          "search.kafka.topic 설정의 product-created/product-updated/product-deleted 토픽으로 샘플 상품 payload를 각각 20건씩 발행합니다.")
  @ApiResponse(responseCode = "200", description = "Kafka 토픽 테스트 발행 요청 성공")
  @ApiErrorResponses
  @RequestMapping(
      value = "/topicProduceTest",
      method = {RequestMethod.GET, RequestMethod.POST})
  public ResponseEntity<TopicProduceTestResponse> topicProduceTest() {
    return ResponseEntity.ok(productTopicProduceTestService.produceSamples());
  }

  private JobParameters newJobParameters() {
    return new JobParametersBuilder()
        .addLong("run.id", System.currentTimeMillis())
        .toJobParameters();
  }
}
