package com.openat.productimport.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.productimport.domain.error.ProductImportErrorCode;
import com.openat.productimport.domain.model.ProductImportItem;
import com.openat.productimport.domain.model.ProductImportJob;
import com.openat.productimport.domain.model.ProductImportSourceType;
import com.openat.productimport.infrastructure.persistence.ProductImportItemRepository;
import com.openat.productimport.infrastructure.persistence.ProductImportJobRepository;
import com.openat.productimport.infrastructure.source.ProductImportSourceResolver;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductImportJobService {

  private final ProductImportJobRepository jobRepository;
  private final ProductImportItemRepository itemRepository;
  private final ProductImportSourceResolver sourceResolver;
  private final ProductImportProcessor processor;

  public ProductImportJob start(
      UUID sellerId, ProductImportSourceType sourceType, String location, boolean dryRun) {
    String normalizedLocation = location.trim();
    sourceResolver.resolve(sourceType).validateLocation(normalizedLocation);
    ProductImportJob job =
        jobRepository.save(
            ProductImportJob.create(sellerId, sourceType, normalizedLocation, dryRun));
    processor.process(job.getId());
    return job;
  }

  public ProductImportJob get(UUID sellerId, UUID jobId) {
    ProductImportJob job =
        jobRepository
            .findById(jobId)
            .orElseThrow(() -> new BusinessException(ProductImportErrorCode.JOB_NOT_FOUND));
    if (!job.getSellerId().equals(sellerId)) {
      throw new BusinessException(ProductImportErrorCode.JOB_NOT_OWNER);
    }
    return job;
  }

  public Page<ProductImportItem> getItems(UUID sellerId, UUID jobId, Pageable pageable) {
    get(sellerId, jobId);
    return itemRepository.findAllByJobId(jobId, pageable);
  }
}
