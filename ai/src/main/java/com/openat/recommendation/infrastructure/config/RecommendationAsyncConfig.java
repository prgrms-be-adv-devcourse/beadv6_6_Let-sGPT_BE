package com.openat.recommendation.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RecommendationAsyncConfig {

  /**
   * 추천 스케줄 태스크 전용 스케줄러. {@code spring.threads.virtual.enabled=true}에서 Boot의 기본
   * {@code SimpleAsyncTaskScheduler}는 fixed-delay 태스크를 직렬로 몰아 한 refresh의 느린 블로킹
   * 호출이 다른 refresh를 지연시킨다. 풀 크기 2의 전용 스케줄러로 PopularProductsCache와
   * OpenDropCache의 갱신이 서로 독립된 스레드에서 돌게 해 상호 간섭을 없앤다.
   */
  @Bean("recommendationTaskScheduler")
  public ThreadPoolTaskScheduler recommendationTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("rec-sched-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

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
