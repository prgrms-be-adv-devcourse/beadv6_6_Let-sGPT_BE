package com.openat.search.product.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

class ProductDbToEsBulkWriterTest {

  @Test
  void sendsFiftyDocumentsInOneBulkSaveCall() throws Exception {
    ProductSearchDocumentRepository repository = mock(ProductSearchDocumentRepository.class);
    ProductDbToEsJobConfig jobConfig = new ProductDbToEsJobConfig();
    ItemWriter<CompletableFuture<ProductDocument>> writer =
        jobConfig.productDbToEsWriter(repository);
    List<CompletableFuture<ProductDocument>> chunkItems = new ArrayList<>(50);
    for (int index = 0; index < 50; index++) {
      chunkItems.add(CompletableFuture.completedFuture(document(index)));
    }

    writer.write(new Chunk<>(chunkItems));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<ProductDocument>> documentsCaptor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(repository, times(1)).saveAll(documentsCaptor.capture());
    assertThat(documentsCaptor.getValue())
        .extracting(ProductDocument::id)
        .containsExactlyElementsOf(
            chunkItems.stream().map(CompletableFuture::join).map(ProductDocument::id).toList());
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
