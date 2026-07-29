package com.openat.recommendation.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RecommendationAsyncConfig {

  /**
   * 가상 스레드에서 Boot 기본 스케줄러는 fixed-delay 태스크를 직렬로 몰아 서로를 지연시킨다.
   *
   * <p>풀 크기 3 = 5분 주기 refresh 두 개를 각각 격리 + 오래 도는 DETAIL 프리배치 전용 한 개.
   */
  @Bean("recommendationTaskScheduler")
  public ThreadPoolTaskScheduler recommendationTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(3);
    scheduler.setThreadNamePrefix("rec-sched-");
    scheduler.setDaemon(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  /** 블로킹 HTTP 팬아웃이라 태스크마다 가상 스레드를 쓴다. 동시성 상한은 상위 Semaphore가 잡는다. */
  @Bean("recommendationExecutor")
  public Executor recommendationExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
