package com.openat.drop.infrastructure.schedule;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.openat.config.DropProperties;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 스케줄러")
class DropSchedulerTest {

  @Mock private TaskScheduler taskScheduler;
  @Mock private DropCacheWarmer dropCacheWarmer;
  @Mock private DropCloseService dropCloseService;

  private final DropProperties properties =
      new DropProperties(
          Duration.ofMinutes(5),
          Duration.ofMinutes(10),
          Duration.ofDays(7),
          Duration.ofHours(1),
          Duration.ofSeconds(10));

  private DropScheduler dropScheduler;

  @BeforeEach
  void setUp() {
    dropScheduler = new DropScheduler(taskScheduler, dropCacheWarmer, dropCloseService, properties);
  }

  @Test
  @DisplayName("워밍 시각이 이미 지난(오픈된) 드롭은 동기로 워밍하고 비동기 예약하지 않는다")
  void schedule_warmTimePassed_warmsSynchronously() {
    // given
    UUID dropId = UUID.randomUUID();
    Instant alreadyOpenAt = Instant.now().minusSeconds(60);

    // when
    dropScheduler.schedule(dropId, alreadyOpenAt, null);

    // then
    then(dropCacheWarmer).should().warm(dropId);
    then(taskScheduler).should(never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("오픈이 충분히 미래면 동기로 워밍하지 않고 워밍을 예약한다")
  void schedule_futureOpen_schedulesWarmTask() {
    // given
    UUID dropId = UUID.randomUUID();
    Instant futureOpenAt = Instant.now().plusSeconds(3600);

    // when
    dropScheduler.schedule(dropId, futureOpenAt, null);

    // then
    then(dropCacheWarmer).should(never()).warm(any());
    then(taskScheduler).should().schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("종료 시각 없는 드롭의 예약 워밍이 실패해도 완료된 작업 참조를 정리한다")
  void scheduledWarm_withoutCloseAt_failureCleansTaskReference() {
    UUID dropId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> warmFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
    org.mockito.Mockito.doReturn(warmFuture)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    willThrow(new IllegalStateException("redis unavailable")).given(dropCacheWarmer).warm(dropId);
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), null);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));

    assertThatThrownBy(() -> taskCaptor.getValue().run())
        .isInstanceOf(IllegalStateException.class);
    dropScheduler.cancel(dropId);

    then(warmFuture).should(never()).cancel(false);
  }

  @Test
  @DisplayName("드롭 삭제 커밋 뒤 예약만 취소한다")
  void onDropDeleted_cancelsSchedule() {
    // given
    UUID dropId = UUID.randomUUID();

    // when
    dropScheduler.onDropDeleted(new DropDeletedEvent(dropId, true));

    // then
    then(taskScheduler).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("드롭 종료 커밋 뒤 예약만 취소한다")
  void onDropClosed_cancelsSchedule() {
    // given
    UUID dropId = UUID.randomUUID();

    // when
    dropScheduler.onDropClosed(new DropClosedEvent(dropId));

    // then
    then(taskScheduler).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("예약 종료가 실패하면 설정된 간격 뒤 다시 시도한다")
  void scheduledClose_failureSchedulesRetry() {
    // given
    UUID dropId = UUID.randomUUID();
    Instant closeAt = Instant.now().plusSeconds(3600);
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> initialFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
    @SuppressWarnings("unchecked")
    ScheduledFuture<Object> retryFuture = org.mockito.Mockito.mock(ScheduledFuture.class);
    org.mockito.Mockito.doReturn(initialFuture, retryFuture)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    willThrow(new IllegalStateException("redis unavailable")).given(dropCloseService).close(dropId);
    dropScheduler.schedule(dropId, Instant.now().minusSeconds(60), closeAt);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));

    // when
    taskCaptor.getValue().run();

    // then
    then(taskScheduler).should(times(2)).schedule(taskCaptor.capture(), any(Instant.class));
    then(dropCloseService).should().close(dropId);
  }
}
