package com.openat.drop.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropStockCommand;
import com.openat.drop.application.usecase.DropStockUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.StockCommandResult;
import com.openat.drop.domain.repository.StockMutation;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** '@Transactional' 제외: UNIQUE 충돌 시 rollback-only 전파 방지 */
@Service
@RequiredArgsConstructor
public class DropStockService implements DropStockUseCase {

  private final DropCacheRepository dropCacheRepository;
  private final StockHistoryRecorder stockHistoryRecorder;

  @Override
  public long deduct(DropStockCommand command) {
    StockMutation mutation = command.toMutation();
    StockCommandResult reservation = dropCacheRepository.deduct(mutation, Instant.now());
    return switch (reservation.status()) {
      case OK -> persistOrCompensate(mutation, StockChangeType.DEDUCT, reservation.remaining());
      case DUPLICATE -> reservation.remaining();
      case NOT_OPEN -> throw new BusinessException(DropErrorCode.NOT_OPEN);
      case SOLD_OUT -> throw new BusinessException(DropErrorCode.SOLD_OUT);
      case LIMIT_EXCEEDED -> throw new BusinessException(DropErrorCode.LIMIT_EXCEEDED);
      case CLOSED -> throw new BusinessException(DropErrorCode.CLOSED);
      case NOT_CACHED -> throw new BusinessException(DropErrorCode.NOT_CACHED);
    };
  }

  private long persistOrCompensate(
      StockMutation mutation, StockChangeType changeType, long remaining) {
    try {
      boolean newlyPersisted = tryRecordHistory(mutation, changeType);
      if (!newlyPersisted) {
        compensateCache(mutation, changeType);
      }
      return remaining;
    } catch (RuntimeException persistenceFailure) {
      compensateCache(mutation, changeType);
      throw persistenceFailure;
    }
  }

  private boolean tryRecordHistory(StockMutation mutation, StockChangeType changeType) {
    try {
      stockHistoryRecorder.record(mutation, changeType);
      return true;
    } catch (DataIntegrityViolationException alreadyPersisted) {
      return false;
    }
  }

  private void compensateCache(StockMutation mutation, StockChangeType changeType) {
    switch (changeType) {
      case DEDUCT -> dropCacheRepository.compensateDeduct(mutation);
      case ROLLBACK -> dropCacheRepository.compensateRollback(mutation);
    }
  }
}
