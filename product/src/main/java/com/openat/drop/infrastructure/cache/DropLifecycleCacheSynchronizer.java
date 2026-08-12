package com.openat.drop.infrastructure.cache;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.service.DropCacheRecoveryService;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.repository.DropCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class DropLifecycleCacheSynchronizer {

  private final DropCacheRepository dropCacheRepository;
  private final DropCacheRecoveryService dropCacheRecoveryService;
  private final DropCacheWarmer dropCacheWarmer;

  @Order(0)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void closeBeforeCommit(DropClosedEvent event) {
    dropCacheRepository.markClosed(event.dropId());
  }

  @Order(0)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void evictBeforeCommit(DropDeletedEvent event) {
    if (event.beforeOpen() && !dropCacheRepository.evictBeforeOpen(event.dropId())) {
      throw new BusinessException(DropErrorCode.OPEN_EXISTS);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
  public void restoreAfterCloseRollback(DropClosedEvent event) {
    try {
      dropCacheRecoveryService.restoreCloseAt(event.dropId());
    } catch (RuntimeException recoveryFailure) {
      log.error(
          "Failed to restore drop cache after close rollback. dropId={}",
          event.dropId(),
          recoveryFailure);
    }
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
  public void restoreAfterDeleteRollback(DropDeletedEvent event) {
    if (!event.beforeOpen()) {
      return;
    }
    try {
      dropCacheWarmer.warm(event.dropId());
    } catch (RuntimeException recoveryFailure) {
      log.error(
          "Failed to warm drop cache after delete rollback. dropId={}",
          event.dropId(),
          recoveryFailure);
    }
  }
}
