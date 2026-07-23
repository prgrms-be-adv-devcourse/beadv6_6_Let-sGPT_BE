package com.openat.recommendation.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecommendationAsyncConfig {

  @Bean("recommendationExecutor")
  public Executor recommendationExecutor(
      @Value("${recommendation.async.pool-size:16}") int poolSize) {
    ThreadFactory threadFactory =
        new ThreadFactory() {
          private final AtomicInteger counter = new AtomicInteger();

          @Override
          public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "rec-async-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          }
        };
    return Executors.newFixedThreadPool(poolSize, threadFactory);
  }
}
