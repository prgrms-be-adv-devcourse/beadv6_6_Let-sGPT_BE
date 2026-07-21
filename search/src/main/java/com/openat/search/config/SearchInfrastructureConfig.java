package com.openat.search.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository(databaseType = "postgres", tablePrefix = "search.batch_")
@EnableKafka
public class SearchInfrastructureConfig {

  @Value("${search.batch.thread-pool-size:5}")
  private int threadPoolSize;

  @Bean(name = "searchBatchTaskExecutor")
  public ThreadPoolTaskExecutor searchBatchTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threadPoolSize);
    executor.setMaxPoolSize(threadPoolSize);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("search-db-to-es-");
    executor.initialize();
    return executor;
  }
}
