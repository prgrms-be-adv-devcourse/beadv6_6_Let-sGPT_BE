package com.openat.drop.domain.repository;

import java.time.Duration;
import java.util.UUID;

/** Separates live stock changes from ledger snapshot publication. */
public interface DropRecoveryRepository {
  boolean beginChange(UUID dropId, UUID attemptId);

  void completeChange(UUID dropId, UUID attemptId);

  boolean beginRecovery(UUID dropId, UUID owner, Duration lease);

  void completeRecovery(UUID dropId, UUID owner);
}
