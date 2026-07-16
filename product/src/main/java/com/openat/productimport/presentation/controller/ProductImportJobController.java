package com.openat.productimport.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.productimport.application.service.ProductImportJobService;
import com.openat.productimport.domain.model.ProductImportJob;
import com.openat.productimport.presentation.dto.ProductImportItemResponse;
import com.openat.productimport.presentation.dto.ProductImportJobResponse;
import com.openat.productimport.presentation.dto.ProductImportStartRequest;
import com.openat.support.auth.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/products/import-jobs")
@RequiredArgsConstructor
public class ProductImportJobController implements ProductImportJobApiSpec {

  private final ProductImportJobService jobService;

  @Override
  @PostMapping
  public ResponseEntity<ProductImportJobResponse> start(
      @CurrentUser UUID sellerId, @Valid @RequestBody ProductImportStartRequest request) {
    ProductImportJob job =
        jobService.start(sellerId, request.sourceType(), request.location(), request.isDryRun());
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{jobId}")
            .buildAndExpand(job.getId())
            .toUri();
    return ResponseEntity.accepted().location(location).body(ProductImportJobResponse.from(job));
  }

  @Override
  @GetMapping("/{jobId}")
  public ResponseEntity<ProductImportJobResponse> getJob(
      @CurrentUser UUID sellerId, @PathVariable UUID jobId) {
    return ResponseEntity.ok(ProductImportJobResponse.from(jobService.get(sellerId, jobId)));
  }

  @Override
  @GetMapping("/{jobId}/items")
  public ResponseEntity<PageResponse<ProductImportItemResponse>> getItems(
      @CurrentUser UUID sellerId,
      @PathVariable UUID jobId,
      @PageableDefault(size = 50) Pageable pageable) {
    return ResponseEntity.ok(
        PageResponse.of(
            jobService.getItems(sellerId, jobId, pageable).map(ProductImportItemResponse::from)));
  }
}
