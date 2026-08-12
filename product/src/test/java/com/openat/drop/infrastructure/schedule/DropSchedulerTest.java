package com.openat.drop.infrastructure.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.openat.common.exception.BusinessException;
import com.openat.config.DropProperties;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.event.DropRegisteredEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
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

    assertThatThrownBy(() -> taskCaptor.getValue().run()).isInstanceOf(IllegalStateException.class);
    dropScheduler.cancel(dropId);

    then(warmFuture).should(never()).cancel(false);
    then(taskScheduler).should(times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("일시적으로 복구가 거절된 예약 워밍은 1초 뒤 재시도하고 성공 뒤 정리한다")
  void scheduledWarm_busyThenSuccess_retriesAndCleansReference() {
    UUID dropId = UUID.randomUUID();
    ScheduledFuture<?> initialFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
    doReturn(initialFuture, retryFuture)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    doThrow(busy()).doNothing().when(dropCacheWarmer).warm(dropId);
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), null);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));

    Instant beforeRetry = Instant.now();
    taskCaptor.getValue().run();

    ArgumentCaptor<Instant> timeCaptor = ArgumentCaptor.forClass(Instant.class);
    then(taskScheduler).should(times(2)).schedule(taskCaptor.capture(), timeCaptor.capture());
    assertThat(timeCaptor.getValue())
        .isBetween(beforeRetry.plusSeconds(1), Instant.now().plusSeconds(1));
    taskCaptor.getValue().run();
    dropScheduler.cancel(dropId);

    then(dropCacheWarmer).should(times(2)).warm(dropId);
    then(initialFuture).should(never()).cancel(false);
    then(retryFuture).should(never()).cancel(false);
    then(taskScheduler).should(times(2)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("종료 전의 반복 BUSY는 재시도하되 기존 종료 예약은 유지한다")
  void scheduledWarm_repeatedBusy_preservesCloseTask() {
    UUID dropId = UUID.randomUUID();
    Instant closeAt = Instant.now().plusSeconds(7200);
    ScheduledFuture<?> initialFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> closeFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> firstRetry = mock(ScheduledFuture.class);
    ScheduledFuture<?> secondRetry = mock(ScheduledFuture.class);
    doReturn(initialFuture, closeFuture, firstRetry, secondRetry)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    doThrow(busy()).doThrow(busy()).doNothing().when(dropCacheWarmer).warm(dropId);
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), closeAt);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should(times(2)).schedule(taskCaptor.capture(), any(Instant.class));
    Runnable closeTask = taskCaptor.getAllValues().get(1);

    taskCaptor.getAllValues().get(0).run();
    then(taskScheduler).should(times(3)).schedule(taskCaptor.capture(), any(Instant.class));
    taskCaptor.getValue().run();
    then(taskScheduler).should(times(4)).schedule(taskCaptor.capture(), any(Instant.class));
    taskCaptor.getValue().run();

    then(dropCacheWarmer).should(times(3)).warm(dropId);
    then(closeFuture).should(never()).cancel(false);
    then(dropCloseService).shouldHaveNoInteractions();
    closeTask.run();
    then(dropCloseService).should().close(dropId);
    then(taskScheduler).should(times(4)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("재시도 시각이 종료 시각에 닿으면 워밍 재시도 없이 종료 예약만 유지한다")
  void scheduledWarm_retryAtCloseAt_stopsRetryAndPreservesCloseTask() {
    UUID dropId = UUID.randomUUID();
    Instant closeAt = Instant.now().plusSeconds(7200);
    ScheduledFuture<?> initialFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> closeFuture = mock(ScheduledFuture.class);
    doReturn(initialFuture, closeFuture)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    willThrow(busy()).given(dropCacheWarmer).warm(dropId);
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), closeAt);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should(times(2)).schedule(taskCaptor.capture(), any(Instant.class));
    Instant oneSecondBeforeClose = closeAt.minusSeconds(1);

    try (MockedStatic<Instant> time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
      time.when(Instant::now).thenReturn(oneSecondBeforeClose);
      taskCaptor.getAllValues().get(0).run();
    }

    then(taskScheduler).should(times(2)).schedule(any(Runnable.class), any(Instant.class));
    then(closeFuture).should(never()).cancel(false);
    taskCaptor.getAllValues().get(1).run();
    then(dropCloseService).should().close(dropId);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("취소 또는 재등록으로 대체된 이전 워밍 작업은 실행하지 않는다")
  void scheduledWarm_cancelledOrReplaced_oldRunnableDoesNotWarm(boolean replace) {
    UUID dropId = UUID.randomUUID();
    ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> newFuture = mock(ScheduledFuture.class);
    doReturn(oldFuture, newFuture)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), null);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));
    Runnable oldTask = taskCaptor.getValue();

    if (replace) {
      dropScheduler.schedule(dropId, Instant.now().plusSeconds(7200), null);
    } else {
      dropScheduler.cancel(dropId);
    }
    oldTask.run();

    then(dropCacheWarmer).shouldHaveNoInteractions();
    then(oldFuture).should().cancel(false);
    then(newFuture).should(never()).cancel(false);
    if (replace) {
      then(taskScheduler).should(times(2)).schedule(taskCaptor.capture(), any(Instant.class));
      taskCaptor.getValue().run();
      then(dropCacheWarmer).should().warm(dropId);
    }
  }

  @Test
  @DisplayName("워밍이 BUSY로 끝나기 전에 취소되면 새 재시도를 등록하지 않는다")
  void scheduledWarm_cancelledDuringWarm_doesNotRegisterRetry() {
    UUID dropId = UUID.randomUUID();
    ScheduledFuture<?> initialFuture = mock(ScheduledFuture.class);
    doReturn(initialFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    doAnswer(
            invocation -> {
              dropScheduler.cancel(dropId);
              throw busy();
            })
        .when(dropCacheWarmer)
        .warm(dropId);
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), null);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));

    taskCaptor.getValue().run();

    then(initialFuture).should().cancel(false);
    then(taskScheduler).should(times(1)).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("재시도 등록 도중 취소되면 뒤늦게 반환된 future도 취소한다")
  void scheduledWarm_cancelledWhileRegisteringRetry_cancelsLateFuture() {
    UUID dropId = UUID.randomUUID();
    ScheduledFuture<?> initialFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
    doReturn(initialFuture)
        .doAnswer(
            invocation -> {
              dropScheduler.cancel(dropId);
              return retryFuture;
            })
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    willThrow(busy()).given(dropCacheWarmer).warm(dropId);
    dropScheduler.schedule(dropId, Instant.now().plusSeconds(3600), null);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));

    taskCaptor.getValue().run();

    then(initialFuture).should().cancel(false);
    then(retryFuture).should().cancel(false);
    then(taskScheduler).should(times(2)).schedule(taskCaptor.capture(), any(Instant.class));
    taskCaptor.getValue().run();
    then(dropCacheWarmer).should(times(1)).warm(dropId);
  }

  @Test
  @DisplayName("즉시 워밍의 BUSY는 기동 실패로 전파하고 지연 재시도로 숨기지 않는다")
  void schedule_immediateBusy_propagatesWithoutRetry() {
    UUID dropId = UUID.randomUUID();
    BusinessException failure = busy();
    willThrow(failure).given(dropCacheWarmer).warm(dropId);

    assertThatThrownBy(
            () ->
                dropScheduler.schedule(
                    dropId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)))
        .isSameAs(failure);

    then(taskScheduler).shouldHaveNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("등록 커밋 뒤 즉시 워밍의 BUSY는 재시도하고 종료 예약도 유지한다")
  void onDropRegistered_immediateBusy_retriesAndPreservesClose(boolean withClose) {
    UUID dropId = UUID.randomUUID();
    Instant closeAt = withClose ? Instant.now().plusSeconds(3600) : null;
    ScheduledFuture<?> retryFuture = mock(ScheduledFuture.class);
    ScheduledFuture<?> closeFuture = mock(ScheduledFuture.class);
    doReturn(retryFuture, closeFuture)
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));
    doThrow(busy()).doNothing().when(dropCacheWarmer).warm(dropId);

    dropScheduler.onDropRegistered(
        new DropRegisteredEvent(dropId, Instant.now().minusSeconds(60), closeAt));

    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Instant> timeCaptor = ArgumentCaptor.forClass(Instant.class);
    then(taskScheduler)
        .should(times(withClose ? 2 : 1))
        .schedule(taskCaptor.capture(), timeCaptor.capture());
    taskCaptor.getAllValues().get(0).run();
    then(dropCacheWarmer).should(times(2)).warm(dropId);
    if (withClose) {
      assertThat(timeCaptor.getAllValues().get(1)).isEqualTo(closeAt);
      then(closeFuture).should(never()).cancel(false);
      taskCaptor.getAllValues().get(1).run();
      then(dropCloseService).should().close(dropId);
    } else {
      dropScheduler.cancel(dropId);
      then(retryFuture).should(never()).cancel(false);
    }
    then(taskScheduler)
        .should(times(withClose ? 2 : 1))
        .schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  @DisplayName("커밋 뒤 즉시 워밍의 일반 오류는 재시도 없이 원래 예외를 전파한다")
  void scheduleAfterCommit_nonBusyFailure_propagatesWithoutRetry() {
    UUID dropId = UUID.randomUUID();
    IllegalStateException failure = new IllegalStateException("redis unavailable");
    willThrow(failure).given(dropCacheWarmer).warm(dropId);

    assertThatThrownBy(
            () ->
                dropScheduler.scheduleAfterCommit(
                    dropId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)))
        .isSameAs(failure);

    then(taskScheduler).shouldHaveNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("즉시 워밍 중 취소나 재등록이 발생하면 이전 워밍과 종료 예약을 추가하지 않는다")
  void scheduleAfterCommit_cancelledOrReplacedDuringWarm_doesNotRegisterOldTasks(boolean replace) {
    UUID dropId = UUID.randomUUID();
    ScheduledFuture<?> newFuture = mock(ScheduledFuture.class);
    if (replace) {
      doReturn(newFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }
    doAnswer(
            invocation -> {
              if (replace) {
                dropScheduler.scheduleAfterCommit(dropId, Instant.now().plusSeconds(7200), null);
              } else {
                dropScheduler.cancel(dropId);
              }
              throw busy();
            })
        .doNothing()
        .when(dropCacheWarmer)
        .warm(dropId);

    dropScheduler.scheduleAfterCommit(
        dropId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));

    if (replace) {
      ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
      then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));
      then(newFuture).should(never()).cancel(false);
      taskCaptor.getValue().run();
      then(dropCacheWarmer).should(times(2)).warm(dropId);
    } else {
      then(taskScheduler).shouldHaveNoInteractions();
    }
    then(dropCloseService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("즉시 워밍 뒤 종료 예약 등록 중 취소되면 늦게 생성된 종료 future도 취소한다")
  void scheduleAfterCommit_cancelledWhileRegisteringClose_cancelsLateCloseFuture() {
    UUID dropId = UUID.randomUUID();
    ScheduledFuture<?> closeFuture = mock(ScheduledFuture.class);
    doAnswer(
            invocation -> {
              dropScheduler.cancel(dropId);
              return closeFuture;
            })
        .when(taskScheduler)
        .schedule(any(Runnable.class), any(Instant.class));

    dropScheduler.scheduleAfterCommit(
        dropId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));

    then(closeFuture).should().cancel(false);
    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));
    taskCaptor.getValue().run();
    then(dropCloseService).shouldHaveNoInteractions();
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

  @Test
  @DisplayName("삭제 보상은 드롭 오픈 예약 시각을 기다리지 않고 즉시 복구한다")
  void recoverAfterRollback_warmsImmediately() {
    UUID dropId = UUID.randomUUID();
    dropScheduler.recoverAfterRollback(dropId, null);
    then(dropCacheWarmer).should().warm(dropId);
    then(taskScheduler).shouldHaveNoInteractions();
  }

  private BusinessException busy() {
    return new BusinessException(DropErrorCode.STOCK_CHANGE_IN_PROGRESS);
  }
}
