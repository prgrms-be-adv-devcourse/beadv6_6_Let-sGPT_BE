package com.openat.search.product.infrastructure.batch;

import com.openat.search.product.application.service.ProductEmbeddingService;
import com.openat.search.product.domain.model.Product;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.infrastructure.item.support.builder.SynchronizedItemStreamReaderBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class ProductDbToEsJobConfig {

  public static final String PRODUCT_DB_TO_ES_JOB = "productDbToEsJob";

  @Value("${search.batch.chunk-size:100}")
  private int chunkSize;

  @Bean
  public Job productDbToEsJob(JobRepository jobRepository, Step productDbToEsStep) {
    return new JobBuilder(PRODUCT_DB_TO_ES_JOB, jobRepository).start(productDbToEsStep).build();
  }

  @Bean
  public Step productDbToEsStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      SynchronizedItemStreamReader<Product> productDbToEsReader,
      ItemProcessor<Product, ProductDocument> productDbToEsProcessor,
      ItemWriter<ProductDocument> productDbToEsWriter,
      ProductDbToEsReadLoggingListener productDbToEsReadLoggingListener,
      @Qualifier("searchBatchTaskExecutor") TaskExecutor searchBatchTaskExecutor) {
    return new StepBuilder("productDbToEsStep", jobRepository)
        .<Product, ProductDocument>chunk(chunkSize, transactionManager)
        .reader(productDbToEsReader)
        .processor(
            productDbToEsProcessor) // DB에서 읽은 데이터를 Elasticsearch에 저장하기 전에 중간 가공하는 단계(2026.07.08)
        .writer(productDbToEsWriter)
        .listener((ItemReadListener<Product>) productDbToEsReadLoggingListener)
        .listener((StepExecutionListener) productDbToEsReadLoggingListener)
        .taskExecutor(searchBatchTaskExecutor)
        .build();
  }

  @Bean
  public SynchronizedItemStreamReader<Product> productDbToEsReader(
      EntityManagerFactory entityManagerFactory) {
    // DB product select point: JpaPagingItemReader reads product rows by search.batch.chunk-size.
    JpaPagingItemReader<Product> delegate =
        new JpaPagingItemReaderBuilder<Product>()
            .name("productDbToEsJpaReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString("select p from Product p order by p.createdAt asc")
            .pageSize(chunkSize)
            .build();

    return new SynchronizedItemStreamReaderBuilder<Product>().delegate(delegate).build();
  }

  @Bean
  public ItemProcessor<Product, ProductDocument> productDbToEsProcessor(
      ProductEmbeddingService productEmbeddingService) {
    return product -> productEmbeddingService.applyEmbedding(ProductDocument.from(product));
  }

  @Bean
  public ProductDbToEsReadLoggingListener productDbToEsReadLoggingListener() {
    return new ProductDbToEsReadLoggingListener(chunkSize);
  }

  @Bean
  public ItemWriter<ProductDocument> productDbToEsWriter(
      ProductSearchDocumentRepository productSearchDocumentRepository) {
    return chunk -> {
      // 실제 Elasticsearch 색인 지점입니다.
      // Spring Batch가 읽고 가공한 ProductDocument 목록을 chunk 단위로 모아서 저장합니다.
      // 현재 chunk-size 기본값은 100이며, 이 documents 목록이 추후 bulk index 작업의 기준 단위입니다.
      List<? extends ProductDocument> documents = chunk.getItems();
      log.info(
          "[product-db-to-es] Elasticsearch bulk index chunk size={}, productIds={}",
          documents.size(),
          documents.stream().map(ProductDocument::id).toList());

      // saveAll 호출 시 ProductSearchDocumentRepository가 Elasticsearch products 인덱스에 문서를 색인합니다.
      productSearchDocumentRepository.saveAll(documents);
    };
  }

  static class ProductDbToEsReadLoggingListener
      implements ItemReadListener<Product>, StepExecutionListener {

    private final int chunkSize;
    private final List<Product> selectedProducts = new ArrayList<>();

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
    public synchronized void afterRead(Product product) {
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

    private String toLogValue(Product product) {
      return "Product{"
          + "id="
          + product.getId()
          + ", sellerId="
          + product.getSellerId()
          + ", name='"
          + product.getName()
          + '\''
          + ", description='"
          + product.getDescription()
          + '\''
          + ", categoryId="
          + (product.getCategory() != null ? product.getCategory().getId() : null)
          + ", categoryName='"
          + (product.getCategory() != null ? product.getCategory().getName() : null)
          + '\''
          + ", price="
          + product.getPrice()
          + ", thumbnailKey='"
          + product.getThumbnailKey()
          + '\''
          + ", imageKeys="
          + product.getImageKeys()
          + '}';
    }
  }
}
