package com.openat.chat.infrastructure.config;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ChatInfrastructureConfig {

  @Bean("chatStreamExecutor")
  public ThreadPoolTaskExecutor chatStreamExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(8);
    executor.setThreadNamePrefix("admin-chat-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    return executor;
  }

  @Bean
  public Clock chatClock() {
    return Clock.systemUTC();
  }

  @Bean(destroyMethod = "shutdownNow", name = "chatTaskExecutor")
  public ExecutorService chatTaskExecutor() {
    return new ThreadPoolExecutor(
        4,
        8,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),
        Thread.ofPlatform().daemon().name("admin-chat-task-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  @Bean
  public TokenCountEstimator chatTokenCountEstimator() {
    return new JTokkitTokenCountEstimator();
  }

  @Bean(destroyMethod = "shutdownNow")
  public ScheduledExecutorService chatStreamScheduler() {
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(
            2, Thread.ofPlatform().daemon().name("admin-chat-scheduler-").factory());
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
