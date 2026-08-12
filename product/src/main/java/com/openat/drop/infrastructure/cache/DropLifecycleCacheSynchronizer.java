package com.openat.drop.infrastructure.cache;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.service.DropCacheRecoveryService;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.infrastructure.schedule.DropScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class DropLifecycleCacheSynchronizer {

  private final DropCacheRepository dropCacheRepository;
  private final DropCacheRecoveryService dropCacheRecoveryService;
  private final DropScheduler dropScheduler;

  @Order(0)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void closeBeforeCommit(DropClosedEvent event) {
    dropCacheRepository.markClosed(event.dropId());
  }

  @Order(0)
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void evictBeforeCommit(DropDeletedEvent event) {
    if (!event.beforeOpen()) {
      return;
    }
    boolean evictedOrAbsent;
    try {
      evictedOrAbsent = dropCacheRepository.evictBeforeOpen(event.dropId());
    } catch (RuntimeException uncertainRemoval) {
      // The command may have executed before its response was lost. Recovery uses stock admission.
      restoreCacheOnRollback(event);
      throw uncertainRemoval;
    }
    if (!evictedOrAbsent) {
      throw new BusinessException(DropErrorCode.OPEN_EXISTS);
    }
    restoreCacheOnRollback(event);
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

  private void restoreCacheOnRollback(DropDeletedEvent event) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_ROLLED_BACK) {
              return;
            }
            try {
              // Read committed metadata in a fresh transaction, not the rolled-back context.
              dropCacheRecoveryService
                  .findActiveDrop(event.dropId())
                  .ifPresent(
                      drop ->
                          dropScheduler.recoverAfterRollback(event.dropId(), drop.getCloseAt()));
            } catch (RuntimeException recoveryFailure) {
              log.error(
                  "Failed to restore drop cache from ledger after delete rollback. dropId={}",
                  event.dropId(),
                  recoveryFailure);
            }
          }
        });
  }
}
