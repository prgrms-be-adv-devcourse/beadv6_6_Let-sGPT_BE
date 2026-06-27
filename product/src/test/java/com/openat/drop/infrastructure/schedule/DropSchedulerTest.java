package com.openat.drop.infrastructure.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.config.DropProperties;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.repository.DropCacheRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 스케줄러")
class DropSchedulerTest {

  @Mock private TaskScheduler taskScheduler;
  @Mock private DropCacheWarmer dropCacheWarmer;
  @Mock private DropCloseService dropCloseService;
  @Mock private DropCacheRepository dropCacheRepository;

  private final DropProperties properties =
      new DropProperties(
          Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(1));

  private DropScheduler dropScheduler;

  @BeforeEach
  void setUp() {
    dropScheduler =
        new DropScheduler(
            taskScheduler, dropCacheWarmer, dropCloseService, dropCacheRepository, properties);
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
  @DisplayName("드롭 삭제 이벤트면 예약을 취소하고 캐시를 비운다")
  void onDropDeleted_cancelsAndEvicts() {
    // given
    UUID dropId = UUID.randomUUID();

    // when
    dropScheduler.onDropDeleted(new DropDeletedEvent(dropId));

    // then
    then(dropCacheRepository).should().evict(dropId);
  }

  @Test
  @DisplayName("드롭 종료 이벤트면 예약을 취소하고 캐시에 종료 표시한다")
  void onDropClosed_cancelsAndMarksClosed() {
    // given
    UUID dropId = UUID.randomUUID();

    // when
    dropScheduler.onDropClosed(new DropClosedEvent(dropId));

    // then
    then(dropCacheRepository).should().markClosed(eq(dropId), any(Instant.class));
  }
}
