package com.openat.drop.infrastructure.schedule;

import com.openat.config.DropProperties;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.event.DropRegisteredEvent;
import com.openat.drop.domain.repository.DropCacheRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DropScheduler {

  private final TaskScheduler taskScheduler;
  private final DropCacheWarmer dropCacheWarmer;
  private final DropCloseService dropCloseService;
  private final DropCacheRepository dropCacheRepository;
  private final DropProperties properties;
  private final Map<UUID, List<ScheduledFuture<?>>> scheduledTasks = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropRegistered(DropRegisteredEvent event) {
    schedule(event.dropId(), event.openAt(), event.closeAt());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropDeleted(DropDeletedEvent event) {
    cancel(event.dropId());
    dropCacheRepository.evict(event.dropId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDropClosed(DropClosedEvent event) {
    cancel(event.dropId());
    dropCacheRepository.markClosed(event.dropId(), Instant.now());
  }

  public void schedule(UUID dropId, Instant openAt, Instant closeAt) {
    cancel(dropId);
    List<ScheduledFuture<?>> tasks = new ArrayList<>();
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
    dropCacheWarmer.warm(dropId);
    if (!closeScheduled) {
      scheduledTasks.remove(dropId, ownTasks);
    }
  }

  private void closeAndCleanup(UUID dropId, List<ScheduledFuture<?>> ownTasks) {
    dropCloseService.close(dropId);
    scheduledTasks.remove(dropId, ownTasks);
  }
}
