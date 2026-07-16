package com.openat.productimport.application.service;

import com.openat.productimport.application.dto.ParsedProductImportRow;
import com.openat.productimport.application.dto.ProductImportRowResult;
import com.openat.productimport.domain.model.ProductImportItem;
import com.openat.productimport.domain.model.ProductImportItemStatus;
import com.openat.productimport.domain.model.ProductImportJob;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import com.openat.productimport.infrastructure.csv.ProductImportManifestReader;
import com.openat.productimport.infrastructure.persistence.ProductImportItemRepository;
import com.openat.productimport.infrastructure.persistence.ProductImportJobRepository;
import com.openat.productimport.infrastructure.source.ProductImportSource;
import com.openat.productimport.infrastructure.source.ProductImportSourceResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportProcessor {

  private static final String MANIFEST_FILE = "products.csv";

  private final ProductImportJobRepository jobRepository;
  private final ProductImportItemRepository itemRepository;
  private final ProductImportSourceResolver sourceResolver;
  private final ProductImportManifestReader manifestReader;
  private final ProductImportRowService rowService;
  private final ProductImportProperties properties;

  @Async("productImportExecutor")
  public void process(UUID jobId) {
    ProductImportJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null) {
      log.warn("Product import job disappeared before execution: {}", jobId);
      return;
    }

    try {
      job.start();
      jobRepository.save(job);

      ProductImportSource source = sourceResolver.resolve(job.getSourceType());
      byte[] manifest =
          source.read(job.getLocation(), MANIFEST_FILE, properties.maxManifestBytes());
      List<ParsedProductImportRow> rows = manifestReader.read(manifest);
      job.setTotalRows(rows.size());
      jobRepository.save(job);

      Set<String> externalIds = new HashSet<>();
      for (ParsedProductImportRow parsed : rows) {
        ProductImportItem item = processRow(job, source, parsed, externalIds);
        itemRepository.save(item);
        job.record(item.getStatus());
        jobRepository.save(job);
      }

      job.complete();
      jobRepository.save(job);
    } catch (Exception exception) {
      log.error("Product import job failed: {}", jobId, exception);
      job.fail(rootMessage(exception));
      jobRepository.save(job);
    }
  }

  private ProductImportItem processRow(
      ProductImportJob job,
      ProductImportSource source,
      ParsedProductImportRow parsed,
      Set<String> externalIds) {
    if (!parsed.isValid()) {
      return failedItem(job.getId(), parsed, parsed.errorMessage());
    }
    if (!externalIds.add(parsed.externalId())) {
      return failedItem(job.getId(), parsed, "products.csv 안에서 external_id가 중복되었습니다.");
    }

    try {
      ProductImportRowResult result =
          rowService.process(
              job.getSellerId(), parsed.row(), source, job.getLocation(), job.isDryRun());
      return ProductImportItem.create(
          job.getId(),
          parsed.rowNumber(),
          parsed.externalId(),
          result.status(),
          result.productId(),
          result.dropId(),
          result.message());
    } catch (Exception exception) {
      log.warn(
          "Product import row failed. jobId={}, row={}, externalId={}",
          job.getId(),
          parsed.rowNumber(),
          parsed.externalId(),
          exception);
      return failedItem(job.getId(), parsed, rootMessage(exception));
    }
  }

  private static ProductImportItem failedItem(
      UUID jobId, ParsedProductImportRow parsed, String message) {
    return ProductImportItem.create(
        jobId,
        parsed.rowNumber(),
        parsed.externalId(),
        ProductImportItemStatus.FAILED,
        null,
        null,
        message);
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }
}
