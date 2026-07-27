package com.openat.recommendation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.recommendation.application.service.OpenDropCache;
import com.openat.recommendation.application.service.PopularProductsCache;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 추천 스케줄 태스크가 전용 스케줄러에서 서로 독립적으로 돈다는 것을 검증한다. 실시간 타이밍은
 * 불안정하므로 (1) 두 refresh가 같은 전용 스케줄러를 지정하는지, (2) 그 스케줄러 풀이 두 태스크를
 * 동시에 돌릴 만큼 크기(>=2)인지, (3) 한 태스크가 블로킹돼도 다른 태스크가 진행되는지를 확인한다.
 */
class RecommendationSchedulerIsolationTest {

  @Test
  @DisplayName("두 refresh 태스크는 같은 전용 스케줄러를 지정한다")
  void bothRefreshTasksTargetTheDedicatedScheduler() throws Exception {
    Scheduled popular =
        PopularProductsCache.class.getMethod("refresh").getAnnotation(Scheduled.class);
    Scheduled openDrop = OpenDropCache.class.getMethod("refresh").getAnnotation(Scheduled.class);

    assertThat(popular.scheduler()).isEqualTo("recommendationTaskScheduler");
    assertThat(openDrop.scheduler()).isEqualTo("recommendationTaskScheduler");
  }

  @Test
  @DisplayName("전용 스케줄러 풀은 두 태스크를 동시에 돌릴 수 있게 최소 2개 스레드다")
  void dedicatedSchedulerHasAtLeastTwoThreads() {
    ThreadPoolTaskScheduler scheduler = new RecommendationAsyncConfig().recommendationTaskScheduler();
    scheduler.initialize();
    try {
      assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(2);
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  @DisplayName("한 태스크가 블로킹돼도 전용 스케줄러의 다른 태스크는 진행된다")
  void slowTaskDoesNotStallAnotherTaskOnTheSameScheduler() throws Exception {
    ThreadPoolTaskScheduler scheduler = new RecommendationAsyncConfig().recommendationTaskScheduler();
    scheduler.initialize();
    CountDownLatch blockerStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    CountDownLatch otherFinished = new CountDownLatch(1);
    try {
      scheduler.execute(
          () -> {
            blockerStarted.countDown();
            try {
              releaseBlocker.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();

      // 첫 태스크가 스레드를 붙잡고 있어도 두 번째 태스크가 진행돼야 한다(독립 실행 증명).
      scheduler.execute(otherFinished::countDown);

      assertThat(otherFinished.await(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseBlocker.countDown();
      scheduler.shutdown();
    }
  }
}
