package com.openat.drop.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DropCacheRepository {
  void warm(DropCacheState state);

  Map<UUID, Long> findRemaining(Collection<UUID> dropIds);

  void markClosed(UUID dropId);

  void restoreCloseAt(UUID dropId, Instant closeAt);

  boolean evictBeforeOpen(UUID dropId);

  StockCommandResult deduct(StockMutation mutation);

  StockCommandResult rollback(StockMutation mutation);

  Optional<Long> compensateDeduct(StockMutation mutation);

  Optional<Long> compensateRollback(StockMutation mutation);
}
