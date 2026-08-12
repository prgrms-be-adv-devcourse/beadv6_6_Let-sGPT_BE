package com.openat.drop.infrastructure.schedule;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class DropScheduler {

  private static final Duration WARM_RETRY_DELAY = Duration.ofSeconds(1);

  private final TaskScheduler taskScheduler;
  private final DropCacheWarmer dropCacheWarmer;
  private final DropCloseService dropCloseService;
  private final DropProperties properties;
  private final Map<UUID, List<ScheduledFuture<?>>> scheduledTasks = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropRegistered(DropRegisteredEvent event) {
    scheduleAfterCommit(event.dropId(), event.openAt(), event.closeAt());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropDeleted(DropDeletedEvent event) {
    cancel(event.dropId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropClosed(DropClosedEvent event) {
    cancel(event.dropId());
  }

  public void schedule(UUID dropId, Instant openAt, Instant closeAt) {
    scheduleAt(dropId, openAt.minus(properties.warmBefore()), closeAt, false);
  }

  public void scheduleAfterCommit(UUID dropId, Instant openAt, Instant closeAt) {
    scheduleAt(dropId, openAt.minus(properties.warmBefore()), closeAt, true);
  }

  public void recoverAfterRollback(UUID dropId, Instant closeAt) {
    scheduleAt(dropId, Instant.now(), closeAt, true);
  }

  private void scheduleAt(UUID dropId, Instant warmAt, Instant closeAt, boolean retryBusy) {
    cancel(dropId);
    List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
    scheduledTasks.put(dropId, tasks);

    boolean closeScheduled = closeAt != null;
    boolean warmTimePassed = !warmAt.isAfter(Instant.now());
    if (warmTimePassed) {
      if (retryBusy) {
        warmAndCleanup(dropId, tasks, closeAt);
      } else {
        dropCacheWarmer.warm(dropId);
      }
    } else {
      registerTask(dropId, tasks, () -> warmAndCleanup(dropId, tasks, closeAt), warmAt);
    }
    if (closeScheduled) {
      registerTask(dropId, tasks, () -> closeAndCleanup(dropId, tasks), closeAt);
    }
    if (tasks.isEmpty()) {
      scheduledTasks.remove(dropId, tasks);
    }
  }

  public void cancel(UUID dropId) {
    List<ScheduledFuture<?>> tasks = scheduledTasks.remove(dropId);
    if (tasks != null) {
      tasks.forEach(task -> task.cancel(false));
    }
  }

  private void warmAndCleanup(UUID dropId, List<ScheduledFuture<?>> ownTasks, Instant closeAt) {
    if (scheduledTasks.get(dropId) != ownTasks
        || (closeAt != null && !Instant.now().isBefore(closeAt))) {
      return;
    }
    boolean retryScheduled = false;
    try {
      dropCacheWarmer.warm(dropId);
    } catch (BusinessException failure) {
      if (failure.getErrorCode() != DropErrorCode.STOCK_CHANGE_IN_PROGRESS) {
        throw failure;
      }
      retryScheduled = retryWarm(dropId, ownTasks, closeAt);
    } finally {
      if (closeAt == null && !retryScheduled) {
        scheduledTasks.remove(dropId, ownTasks);
      }
    }
  }

  private boolean retryWarm(UUID dropId, List<ScheduledFuture<?>> ownTasks, Instant closeAt) {
    Instant retryAt = Instant.now().plus(WARM_RETRY_DELAY);
    if (scheduledTasks.get(dropId) != ownTasks || (closeAt != null && !retryAt.isBefore(closeAt))) {
      return false;
    }
    log.debug("Stock change in progress. Scheduling drop warming retry. dropId={}", dropId);
    ownTasks.removeIf(ScheduledFuture::isDone);
    return registerTask(dropId, ownTasks, () -> warmAndCleanup(dropId, ownTasks, closeAt), retryAt);
  }

  private boolean registerTask(
      UUID dropId, List<ScheduledFuture<?>> ownTasks, Runnable task, Instant at) {
    if (scheduledTasks.get(dropId) != ownTasks) {
      return false;
    }
    ScheduledFuture<?> future = taskScheduler.schedule(task, at);
    if (future == null) {
      return false;
    }
    ownTasks.add(future);
    // Cancellation may have removed this schedule while the future was being registered.
    if (scheduledTasks.get(dropId) != ownTasks) {
      future.cancel(false);
      return false;
    }
    return true;
  }

  private void closeAndCleanup(UUID dropId, List<ScheduledFuture<?>> ownTasks) {
    if (scheduledTasks.get(dropId) != ownTasks) {
      return;
    }
    try {
      dropCloseService.close(dropId);
      scheduledTasks.remove(dropId, ownTasks);
    } catch (RuntimeException closeFailure) {
      if (scheduledTasks.get(dropId) != ownTasks) {
        return;
      }
      log.warn("Failed to close drop. Scheduling retry. dropId={}", dropId, closeFailure);
      ownTasks.removeIf(ScheduledFuture::isDone);
      registerTask(
          dropId,
          ownTasks,
          () -> closeAndCleanup(dropId, ownTasks),
          Instant.now().plus(properties.closeRetryDelay()));
    }
  }
}
