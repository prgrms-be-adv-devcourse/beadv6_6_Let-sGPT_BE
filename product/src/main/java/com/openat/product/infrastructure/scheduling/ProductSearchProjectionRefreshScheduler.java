package com.openat.product.infrastructure.scheduling;

import com.openat.product.application.service.ProductSearchProjectionRefreshBatchProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductSearchProjectionRefreshScheduler {

  private final ProductSearchProjectionRefreshBatchProcessor batchProcessor;

  @Scheduled(
      fixedDelayString =
          "${product.search-projection-refresh.fixed-delay-ms:1000}")
  public void processNextBatch() {
    batchProcessor.processNextBatch();
  }
}
