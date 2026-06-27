package com.openat.drop.domain.repository;

import java.time.Instant;
import java.util.UUID;

public interface DropCacheRepository {
  void warm(DropCacheState state);

  void markClosed(UUID dropId, Instant now);

  void evict(UUID dropId);

  StockCommandResult deduct(StockMutation mutation, Instant now);

  StockCommandResult rollback(StockMutation mutation);

  void compensateDeduct(StockMutation mutation);

  void compensateRollback(StockMutation mutation);
}
