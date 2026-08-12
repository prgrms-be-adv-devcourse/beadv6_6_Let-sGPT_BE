package com.openat.drop.infrastructure.schedule;

import com.openat.config.DropProperties;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.event.DropRegisteredEvent;
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

  private final TaskScheduler taskScheduler;
  private final DropCacheWarmer dropCacheWarmer;
  private final DropCloseService dropCloseService;
  private final DropProperties properties;
  private final Map<UUID, List<ScheduledFuture<?>>> scheduledTasks = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropRegistered(DropRegisteredEvent event) {
    schedule(event.dropId(), event.openAt(), event.closeAt());
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
    cancel(dropId);
    List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
    scheduledTasks.put(dropId, tasks);

    boolean closeScheduled = closeAt != null;
    Instant warmAt = openAt.minus(properties.warmBefore());
    boolean warmTimePassed = !warmAt.isAfter(Instant.now());
    if (warmTimePassed) {
      dropCacheWarmer.warm(dropId);
    } else {
      tasks.add(
          taskScheduler.schedule(() -> warmAndCleanup(dropId, tasks, closeScheduled), warmAt));
    }
    if (closeScheduled) {
      tasks.add(taskScheduler.schedule(() -> closeAndCleanup(dropId, tasks), closeAt));
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

  private void warmAndCleanup(
      UUID dropId, List<ScheduledFuture<?>> ownTasks, boolean closeScheduled) {
    try {
      dropCacheWarmer.warm(dropId);
    } finally {
      if (!closeScheduled) {
        scheduledTasks.remove(dropId, ownTasks);
      }
    }
  }

  private void closeAndCleanup(UUID dropId, List<ScheduledFuture<?>> ownTasks) {
    try {
      dropCloseService.close(dropId);
      scheduledTasks.remove(dropId, ownTasks);
    } catch (RuntimeException closeFailure) {
      if (scheduledTasks.get(dropId) != ownTasks) {
        return;
      }
      log.warn("Failed to close drop. Scheduling retry. dropId={}", dropId, closeFailure);
      ownTasks.removeIf(ScheduledFuture::isDone);
      ScheduledFuture<?> retry =
          taskScheduler.schedule(
              () -> closeAndCleanup(dropId, ownTasks),
              Instant.now().plus(properties.closeRetryDelay()));
      if (retry != null) {
        ownTasks.add(retry);
      }
    }
  }
}
