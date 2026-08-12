package com.openat.drop.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.repository.DropRecoveryRepository;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DropCacheWarmer {

  private final DropRecoveryRepository recoveryRepository;
  private final DropCacheSnapshotWriter snapshotWriter;
  private final Duration lease;

  public DropCacheWarmer(
      DropRecoveryRepository recoveryRepository,
      DropCacheSnapshotWriter snapshotWriter,
      @Value("${drop.recovery.lease:30s}") Duration lease) {
    if (lease == null || lease.toMillis() <= 0) {
      throw new IllegalArgumentException("Recovery lease must be at least one millisecond");
    }
    this.recoveryRepository = recoveryRepository;
    this.snapshotWriter = snapshotWriter;
    this.lease = lease;
  }

  /** Ledger reconstruction; no SQL snapshot is opened before admission succeeds. */
  public void warm(UUID dropId) {
    UUID owner = UUID.randomUUID();
    if (!recoveryRepository.beginRecovery(dropId, owner, lease)) {
      throw new BusinessException(DropErrorCode.STOCK_CHANGE_IN_PROGRESS);
    }
    try {
      snapshotWriter.write(dropId, owner);
    } finally {
      try {
        recoveryRepository.completeRecovery(dropId, owner);
      } catch (RuntimeException releaseFailure) {
        // The lease expires; do not hide the original failure or undo an already published
        // snapshot.
        log.error(
            "Failed to release drop recovery lease. dropId={} owner={}",
            dropId,
            owner,
            releaseFailure);
      }
    }
  }
}
