package com.openat.productimport.infrastructure.config;

import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@EnableAsync
@EnableConfigurationProperties(ProductImportProperties.class)
public class ProductImportConfig {

  @Bean(name = "productImportExecutor")
  public Executor productImportExecutor(ProductImportProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.workerThreads());
    executor.setMaxPoolSize(properties.workerThreads());
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("product-import-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }

  @Bean(destroyMethod = "close")
  public S3Client productImportS3Client(ProductImportProperties properties) {
    return S3Client.builder()
        .region(Region.of(properties.s3Region()))
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();
  }
}
