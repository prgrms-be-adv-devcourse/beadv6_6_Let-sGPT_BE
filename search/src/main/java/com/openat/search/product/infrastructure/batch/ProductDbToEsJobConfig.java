package com.openat.search.product.infrastructure.batch;

import com.openat.search.product.application.service.ProductEmbeddingService;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class ProductDbToEsJobConfig {

  public static final String PRODUCT_DB_TO_ES_JOB = "productDbToEsJob";

  @Value("${search.batch.chunk-size:50}")
  private int chunkSize;

  @Value("${search.batch.page-size:50}")
  private int pageSize;

  @Bean
  public Job productDbToEsJob(JobRepository jobRepository, Step productDbToEsStep) {
    return new JobBuilder(PRODUCT_DB_TO_ES_JOB, jobRepository).start(productDbToEsStep).build();
  }

  @Bean
  public Step productDbToEsStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ItemReader<ProductDocument> productDbToEsReader,
      ItemProcessor<ProductDocument, CompletableFuture<ProductDocument>> productDbToEsProcessor,
      ItemWriter<CompletableFuture<ProductDocument>> productDbToEsWriter,
      ProductDbToEsReadLoggingListener productDbToEsReadLoggingListener) {
    return new StepBuilder("productDbToEsStep", jobRepository)
        .<ProductDocument, CompletableFuture<ProductDocument>>chunk(chunkSize, transactionManager)
        .reader(productDbToEsReader)
        .processor(
            productDbToEsProcessor) // DB에서 읽은 데이터를 Elasticsearch에 저장하기 전에 중간 가공하는 단계(2026.07.08)
        .writer(productDbToEsWriter)
        .listener((ItemReadListener<ProductDocument>) productDbToEsReadLoggingListener)
        .listener((StepExecutionListener) productDbToEsReadLoggingListener)
        .build();
  }

  @Bean
  @StepScope
  public ProductDbPageReader productDbToEsReader(JdbcTemplate jdbcTemplate) {
    return new ProductDbPageReader(jdbcTemplate, pageSize);
  }

  @Bean
  public ItemProcessor<ProductDocument, CompletableFuture<ProductDocument>> productDbToEsProcessor(
      ProductEmbeddingService productEmbeddingService,
      @Qualifier("searchBatchTaskExecutor") ThreadPoolTaskExecutor searchBatchTaskExecutor) {
    return productDocument ->
        CompletableFuture.supplyAsync(
            () -> productEmbeddingService.applyEmbedding(productDocument), searchBatchTaskExecutor);
  }

  @Bean
  public ProductDbToEsReadLoggingListener productDbToEsReadLoggingListener() {
    return new ProductDbToEsReadLoggingListener(chunkSize);
  }

  @Bean
  public ItemWriter<CompletableFuture<ProductDocument>> productDbToEsWriter(
      ProductSearchDocumentRepository productSearchDocumentRepository) {
    return chunk -> {
      List<ProductDocument> documents =
          chunk.getItems().stream().map(CompletableFuture::join).toList();
      log.info(
          "[product-db-to-es] Parallel processing completed. Elasticsearch bulk index chunk size={}, productIds={}",
          documents.size(),
          documents.stream().map(ProductDocument::id).toList());

      productSearchDocumentRepository.saveAll(documents);
    };
  }

  static class ProductDbToEsReadLoggingListener
      implements ItemReadListener<ProductDocument>, StepExecutionListener {

    private final int chunkSize;
    private final List<ProductDocument> selectedProducts = new ArrayList<>();

    ProductDbToEsReadLoggingListener(int chunkSize) {
      this.chunkSize = chunkSize;
    }

    @Override
    public synchronized void beforeStep(StepExecution stepExecution) {
      selectedProducts.clear();
    }

    @Override
    public void beforeRead() {}

    @Override
    public synchronized void afterRead(ProductDocument product) {
      selectedProducts.add(product);
      if (selectedProducts.size() >= chunkSize) {
        logSelectedProducts();
      }
    }

    @Override
    public void onReadError(Exception ex) {
      log.error("[product-db-to-es] Failed while selecting products from DB", ex);
    }

    @Override
    public synchronized ExitStatus afterStep(StepExecution stepExecution) {
      logSelectedProducts();
      return stepExecution.getExitStatus();
    }

    private void logSelectedProducts() {
      if (selectedProducts.isEmpty()) {
        return;
      }

      log.info(
          "[product-db-to-es] DB selected product chunk size={}, products={}",
          selectedProducts.size(),
          selectedProducts.stream().map(this::toLogValue).toList());
      selectedProducts.clear();
    }

    private String toLogValue(ProductDocument product) {
      return "Product{"
          + "id="
          + product.id()
          + ", name='"
          + product.name()
          + '\''
          + ", description='"
          + product.description()
          + '\''
          + ", categoryId="
          + product.categoryId()
          + ", categoryName='"
          + product.categoryName()
          + '\''
          + ", sellerName='"
          + product.sellerName()
          + '\''
          + ", price="
          + product.price()
          + ", thumbnailKey='"
          + product.thumbnailKey()
          + '\''
          + ", imgDescription='"
          + product.imgDescription()
          + '\''
          + ", createdAt="
          + product.createdAt()
          + ", updatedAt="
          + product.updatedAt()
          + ", deletedAt="
          + product.deletedAt()
          + '}';
    }
  }
}
