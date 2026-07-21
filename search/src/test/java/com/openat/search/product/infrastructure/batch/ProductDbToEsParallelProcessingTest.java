package com.openat.search.product.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openat.search.config.SearchInfrastructureConfig;
import com.openat.search.product.application.service.ProductEmbeddingService;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class ProductDbToEsParallelProcessingTest {

  @Test
  void processesFiveEmbeddingRequestsAtTheSameTime() throws Exception {
    SearchInfrastructureConfig infrastructureConfig = new SearchInfrastructureConfig();
    ReflectionTestUtils.setField(infrastructureConfig, "threadPoolSize", 5);
    ThreadPoolTaskExecutor executor = infrastructureConfig.searchBatchTaskExecutor();
    ProductEmbeddingService embeddingService = mock(ProductEmbeddingService.class);
    CountDownLatch fiveRequestsStarted = new CountDownLatch(5);
    CountDownLatch releaseRequests = new CountDownLatch(1);
    AtomicInteger activeRequests = new AtomicInteger();
    AtomicInteger maxActiveRequests = new AtomicInteger();

    when(embeddingService.applyEmbedding(any(ProductDocument.class)))
        .thenAnswer(
            invocation -> {
              int active = activeRequests.incrementAndGet();
              maxActiveRequests.accumulateAndGet(active, Math::max);
              fiveRequestsStarted.countDown();
              try {
                if (!releaseRequests.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("parallel embedding test timed out");
                }
                return invocation.getArgument(0);
              } finally {
                activeRequests.decrementAndGet();
              }
            });

    ProductDbToEsJobConfig jobConfig = new ProductDbToEsJobConfig();
    ItemProcessor<ProductDocument, CompletableFuture<ProductDocument>> processor =
        jobConfig.productDbToEsProcessor(embeddingService, executor);
    List<CompletableFuture<ProductDocument>> futures = new ArrayList<>();

    try {
      for (int index = 0; index < 5; index++) {
        futures.add(processor.process(document(index)));
      }

      assertThat(fiveRequestsStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(executor.getCorePoolSize()).isEqualTo(5);
      assertThat(executor.getMaxPoolSize()).isEqualTo(5);
      assertThat(maxActiveRequests).hasValue(5);

      releaseRequests.countDown();
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    } finally {
      releaseRequests.countDown();
      executor.shutdown();
    }
  }

  private ProductDocument document(int index) {
    return new ProductDocument(
        "00000000-0000-0000-0000-%012d".formatted(index),
        "상품 " + index,
        "상품 설명",
        null,
        null,
        null,
        10_000L,
        null,
        null,
        null,
        Instant.parse("2026-07-20T00:00:00Z"),
        Instant.parse("2026-07-20T00:00:00Z"),
        null);
  }
}
