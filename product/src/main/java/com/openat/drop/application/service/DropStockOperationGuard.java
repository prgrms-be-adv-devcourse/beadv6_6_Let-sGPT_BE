package com.openat.drop.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.repository.DropRecoveryRepository;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 원장 복구가 Redis 변경부터 원장 확정까지 진행 중인 작업을 덮어쓰지 않도록 보호한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DropStockOperationGuard {

  private final DropRecoveryRepository dropRecoveryRepository;

  public <T> T execute(UUID dropId, Supplier<T> action) {
    UUID attemptId = UUID.randomUUID();
    boolean admitted;
    try {
      admitted = dropRecoveryRepository.beginChange(dropId, attemptId);
    } catch (RuntimeException | Error uncertainAdmission) {
      logUncertain(dropId, attemptId, uncertainAdmission);
      throw uncertainAdmission;
    }
    if (!admitted) {
      throw new BusinessException(DropErrorCode.STOCK_CHANGE_IN_PROGRESS);
    }

    T result;
    try {
      result = action.get();
    } catch (BusinessException rejection) {
      completeConfirmedChange(dropId, attemptId);
      throw rejection;
    } catch (RuntimeException | Error uncertainChange) {
      logUncertain(dropId, attemptId, uncertainChange);
      throw uncertainChange;
    }
    completeConfirmedChange(dropId, attemptId);
    return result;
  }

  private void completeConfirmedChange(UUID dropId, UUID attemptId) {
    try {
      dropRecoveryRepository.completeChange(dropId, attemptId);
    } catch (RuntimeException cleanupFailure) {
      // 원장 기록/보상은 이미 확정됐다. 정리 실패를 업무 실패로 바꿔 역보상하지 않는다.
      log.error(
          "Stock change completed but marker cleanup failed; retained marker blocks ledger recovery. dropId={}, attemptId={}",
          dropId,
          attemptId,
          cleanupFailure);
    }
  }

  private void logUncertain(UUID dropId, UUID attemptId, Throwable failure) {
    log.error(
        "Stock change outcome is uncertain; retaining marker to block ledger recovery. dropId={}, attemptId={}",
        dropId,
        attemptId,
        failure);
  }
}
