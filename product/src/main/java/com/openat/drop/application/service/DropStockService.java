package com.openat.drop.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropStockCommand;
import com.openat.drop.application.port.DropStockMetricsPort;
import com.openat.drop.application.usecase.DropStockUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.model.StockCommandStatus;
import com.openat.drop.domain.model.StockHistory;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockCommandResult;
import com.openat.drop.domain.repository.StockHistoryRepository;
import com.openat.drop.domain.repository.StockMutation;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** '@Transactional' 제외: UNIQUE 충돌 시 rollback-only 전파 방지 */
@Service
@RequiredArgsConstructor
public class DropStockService implements DropStockUseCase {

  private final DropCacheRepository dropCacheRepository;
  private final StockHistoryRecorder stockHistoryRecorder;
  private final StockHistoryRepository stockHistoryRepository;
  private final DropRepository dropRepository;
  private final DropStockMetricsPort dropStockMetrics;

  @Override
  public long deduct(DropStockCommand command) {
    StockMutation mutation = command.toMutation();
    StockCommandResult reservation = dropCacheRepository.deduct(mutation, Instant.now());
    StockCommandResult completed = switch (reservation.status()) {
      case OK ->
          persistOrCompensate(mutation, StockChangeType.DEDUCT, reservation.remaining())
              .orElseThrow(() -> new BusinessException(DropErrorCode.NOT_CACHED));
      case DUPLICATE -> completedDuplicate(mutation, StockChangeType.DEDUCT, reservation.remaining());
      case NOT_OPEN -> throw new BusinessException(DropErrorCode.NOT_OPEN);
      case SOLD_OUT -> throw new BusinessException(DropErrorCode.SOLD_OUT);
      case LIMIT_EXCEEDED -> throw new BusinessException(DropErrorCode.LIMIT_EXCEEDED);
      case CLOSED -> throw new BusinessException(DropErrorCode.CLOSED);
      case NOT_CACHED -> throw new BusinessException(DropErrorCode.NOT_CACHED);
    };
    dropStockMetrics.register(mutation.dropId());
    return completed.remaining();
  }

  @Override
  public Optional<Long> rollback(DropStockCommand command) {
    StockMutation mutation = command.toMutation();
    validateRollbackSource(mutation);
    StockCommandResult restoration = dropCacheRepository.rollback(mutation);
    Optional<StockCommandResult> completed = switch (restoration.status()) {
      case OK -> persistOrCompensate(mutation, StockChangeType.ROLLBACK, restoration.remaining());
      case DUPLICATE ->
          Optional.of(
              completedDuplicate(
                  mutation, StockChangeType.ROLLBACK, restoration.remaining()));
      case NOT_CACHED -> rollbackWithoutLiveCache(mutation);
      default -> throw new IllegalStateException("롤백에서 허용되지 않은 캐시 결과 발생: " + restoration.status());
    };
    completed.ifPresent(ignored -> dropStockMetrics.register(mutation.dropId()));
    return completed.map(StockCommandResult::remaining);
  }

  private Optional<StockCommandResult> persistOrCompensate(
      StockMutation mutation, StockChangeType changeType, long remaining) {
    try {
      stockHistoryRecorder.record(mutation, changeType);
      return Optional.of(new StockCommandResult(StockCommandStatus.OK, remaining));
    } catch (DataIntegrityViolationException conflict) {
      Optional<Long> compensated = compensateCache(mutation, changeType);
      validateCommittedHistory(mutation, changeType, conflict);
      return compensated.map(
          actualRemaining ->
              new StockCommandResult(StockCommandStatus.DUPLICATE, actualRemaining));
    } catch (RuntimeException persistenceFailure) {
      compensateCache(mutation, changeType);
      throw persistenceFailure;
    }
  }

  private Optional<StockCommandResult> rollbackWithoutLiveCache(StockMutation mutation) {
    boolean dropIsActive =
        dropRepository
            .findById(mutation.dropId())
            .map(drop -> drop.getStatus() != DropStatus.CLOSE)
            .orElse(false);
    if (dropIsActive) {
      try {
        stockHistoryRecorder.record(mutation, StockChangeType.ROLLBACK);
      } catch (DataIntegrityViolationException conflict) {
        validateCommittedHistory(mutation, StockChangeType.ROLLBACK, conflict);
      }
    }
    return Optional.empty();
  }

  private void validateRollbackSource(StockMutation mutation) {
    StockHistory deduction =
        stockHistoryRepository
            .findByOrderIdAndChangeType(mutation.orderId(), StockChangeType.DEDUCT)
            .orElseThrow(() -> new BusinessException(DropErrorCode.ROLLBACK_NOT_ALLOWED));
    if (!matches(deduction, mutation, StockChangeType.DEDUCT)) {
      throw new BusinessException(DropErrorCode.ROLLBACK_NOT_ALLOWED);
    }
  }

  private StockCommandResult completedDuplicate(
      StockMutation mutation, StockChangeType changeType, long remaining) {
    StockHistory committedHistory =
        stockHistoryRepository
            .findByOrderIdAndChangeType(mutation.orderId(), changeType)
            .orElseThrow(() -> new BusinessException(DropErrorCode.STOCK_CHANGE_IN_PROGRESS));
    if (!matches(committedHistory, mutation, changeType)) {
      throw new BusinessException(DropErrorCode.STOCK_REQUEST_MISMATCH);
    }
    return new StockCommandResult(StockCommandStatus.DUPLICATE, remaining);
  }

  private boolean matches(
      StockHistory history, StockMutation mutation, StockChangeType changeType) {
    int expectedDelta =
        changeType == StockChangeType.DEDUCT ? -mutation.quantity() : mutation.quantity();
    return history.getOrderId().equals(mutation.orderId())
        && history.getDropId().equals(mutation.dropId())
        && history.getBuyerId().equals(mutation.buyerId())
        && history.getChangeType() == changeType
        && history.getQuantityDelta() == expectedDelta;
  }

  private void validateCommittedHistory(
      StockMutation mutation,
      StockChangeType changeType,
      DataIntegrityViolationException persistenceConflict) {
    Optional<StockHistory> committedHistory =
        stockHistoryRepository.findByOrderIdAndChangeType(mutation.orderId(), changeType);
    if (committedHistory.isEmpty()) {
      throw persistenceConflict;
    }
    if (!matches(committedHistory.get(), mutation, changeType)) {
      throw new BusinessException(DropErrorCode.STOCK_REQUEST_MISMATCH);
    }
  }

  private Optional<Long> compensateCache(
      StockMutation mutation, StockChangeType changeType) {
    return switch (changeType) {
      case DEDUCT -> dropCacheRepository.compensateDeduct(mutation);
      case ROLLBACK -> dropCacheRepository.compensateRollback(mutation);
    };
  }
}
