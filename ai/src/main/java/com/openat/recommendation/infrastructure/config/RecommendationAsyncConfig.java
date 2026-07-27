package com.openat.recommendation.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RecommendationAsyncConfig {

  /**
   * 추천 스케줄 태스크 전용 스케줄러. {@code spring.threads.virtual.enabled=true}에서 Boot의 기본
   * {@code SimpleAsyncTaskScheduler}는 fixed-delay 태스크를 직렬로 몰아 한 refresh의 느린 블로킹
   * 호출이 다른 refresh를 지연시킨다. 전용 스케줄러로 PopularProductsCache·OpenDropCache의 갱신이
   * 서로 독립된 스레드에서 돌게 해 상호 간섭을 없앤다.
   *
   * <p>풀 크기는 3. 두 개는 5분 주기 refresh 두 개를 각각 격리하고, 나머지 한 개는 오래 도는
   * DETAIL 프리배치 전용이다. 프리배치가 다수의 LLM 호출로 한 스레드를 길게 점유해도 두 refresh는
   * 자기 스레드를 유지해 굶지 않는다.
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

  /**
   * seed/detail fan-out은 블로킹 HTTP I/O이고 가상 스레드가 켜져 있으므로 태스크마다 가상 스레드를
   * 쓴다. 동시성 상한은 상위 파이프라인의 overload Semaphore가 이미 잡아 주므로 무한 생성이어도
   * 안전하다.
   */
  @Bean("recommendationExecutor")
  public Executor recommendationExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
