package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropStockCommand;
import com.openat.drop.application.port.DropStockMetricsPort;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.Drop;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 재고 서비스")
class DropStockServiceTest {

  @InjectMocks private DropStockService dropStockService;
  @Mock private DropCacheRepository dropCacheRepository;
  @Mock private StockHistoryRecorder stockHistoryRecorder;
  @Mock private StockHistoryRepository stockHistoryRepository;
  @Mock private DropRepository dropRepository;
  @Mock private DropStockMetricsPort dropStockMetrics;

  private final UUID dropId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final UUID buyerId = UUID.randomUUID();
  private final DropStockCommand command = new DropStockCommand(dropId, orderId, buyerId, 2);
  private final StockMutation mutation = new StockMutation(dropId, orderId, buyerId, 2);

  @Nested
  @DisplayName("차감")
  class Deduct {

    @Test
    @DisplayName("OK면 이력을 기록하고 잔여를 반환한다")
    void deduct_ok_recordsAndReturnsRemaining() {
      // given
      givenDeduct(StockCommandStatus.OK, 7);

      // when
      long remaining = dropStockService.deduct(command);

      // then
      assertThat(remaining).isEqualTo(7);
      then(stockHistoryRecorder).should().record(mutation, StockChangeType.DEDUCT);
      then(dropStockMetrics).should().register(dropId);
    }

    @Test
    @DisplayName("DUPLICATE이고 차감 원장이 커밋됐으면 이력을 다시 기록하지 않고 잔여를 반환한다")
    void deduct_duplicateWithCommittedHistory_returnsWithoutRecording() {
      // given
      givenDeduct(StockCommandStatus.DUPLICATE, 7);
      givenCompletedHistory(StockChangeType.DEDUCT);

      // when
      long remaining = dropStockService.deduct(command);

      // then
      assertThat(remaining).isEqualTo(7);
      then(stockHistoryRecorder).should(never()).record(any(), any());
      then(dropStockMetrics).should().register(dropId);
    }

    @Test
    @DisplayName("선행 요청의 차감 원장이 아직 커밋되지 않았으면 DUPLICATE를 성공으로 반환하지 않는다")
    void deduct_duplicateBeforeHistoryCommit_throwsInProgress() throws Exception {
      // given
      given(dropCacheRepository.deduct(eq(mutation), any(Instant.class)))
          .willReturn(
              new StockCommandResult(StockCommandStatus.OK, 7),
              new StockCommandResult(StockCommandStatus.DUPLICATE, 7));
      givenHistory(StockChangeType.DEDUCT, Optional.empty());
      given(dropCacheRepository.compensateDeduct(mutation)).willReturn(Optional.of(9L));
      CountDownLatch persistenceStarted = new CountDownLatch(1);
      CountDownLatch finishPersistence = new CountDownLatch(1);
      willAnswer(
              invocation -> {
                persistenceStarted.countDown();
                if (!finishPersistence.await(1, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test persistence wait timed out");
                }
                throw new IllegalStateException("db insert failed");
              })
          .given(stockHistoryRecorder)
          .record(mutation, StockChangeType.DEDUCT);
      ExecutorService executor = Executors.newSingleThreadExecutor();

      try {
        Future<Long> firstRequest = executor.submit(() -> dropStockService.deduct(command));
        assertThat(persistenceStarted.await(1, TimeUnit.SECONDS)).isTrue();

        // when & then: 두 번째 요청은 첫 요청의 Redis 키를 보지만 커밋된 원장은 보지 못한다.
        assertThatThrownBy(() -> dropStockService.deduct(command))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_CHANGE_IN_PROGRESS);

        finishPersistence.countDown();
        assertThatThrownBy(() -> firstRequest.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        then(dropCacheRepository).should().compensateDeduct(mutation);
      } finally {
        finishPersistence.countDown();
        executor.shutdownNow();
      }
    }

    @Test
    @DisplayName("이미 커밋된 차감 원장과 요청 튜플이 다르면 중복 성공으로 처리하지 않는다")
    void deduct_duplicateWithMismatchedHistory_throwsConflict() {
      // given
      givenDeduct(StockCommandStatus.DUPLICATE, 7);
      givenHistory(
          StockChangeType.DEDUCT,
          Optional.of(deduction(dropId, orderId, UUID.randomUUID(), 2)));

      // when & then
      assertThatThrownBy(() -> dropStockService.deduct(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_REQUEST_MISMATCH);
      then(stockHistoryRecorder).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("SOLD_OUT이면 SOLD_OUT 예외를 던지고 기록하지 않는다")
    void deduct_soldOut_throws() {
      // given
      givenDeduct(StockCommandStatus.SOLD_OUT, 0);

      // when & then
      assertThatThrownBy(() -> dropStockService.deduct(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.SOLD_OUT);
      then(stockHistoryRecorder).should(never()).record(any(), any());
    }

    @Test
    @DisplayName("이력이 이미 존재하면(UNIQUE 위반) 캐시를 역연산하고 멱등 성공으로 처리한다")
    void deduct_insertConflict_compensatesAndReturns() {
      // given
      givenDeduct(StockCommandStatus.OK, 7);
      willThrow(new DataIntegrityViolationException("duplicate"))
          .given(stockHistoryRecorder)
          .record(any(), eq(StockChangeType.DEDUCT));
      given(dropCacheRepository.compensateDeduct(mutation)).willReturn(Optional.of(9L));
      givenCompletedHistory(StockChangeType.DEDUCT);

      // when
      long remaining = dropStockService.deduct(command);

      // then
      assertThat(remaining).isEqualTo(9);
      then(dropCacheRepository).should().compensateDeduct(mutation);
      then(dropStockMetrics).should().register(dropId);
    }

    @Test
    @DisplayName("일시적 DB 오류면 캐시를 역연산하고 예외를 전파한다")
    void deduct_insertError_compensatesAndRethrows() {
      // given
      givenDeduct(StockCommandStatus.OK, 7);
      willThrow(new IllegalStateException("db down"))
          .given(stockHistoryRecorder)
          .record(any(), eq(StockChangeType.DEDUCT));

      // when & then
      assertThatThrownBy(() -> dropStockService.deduct(command))
          .isInstanceOf(IllegalStateException.class);
      then(dropCacheRepository).should().compensateDeduct(mutation);
    }
  }

  @Nested
  @DisplayName("롤백")
  class Rollback {

    @Test
    @DisplayName("OK면 이력을 기록하고 잔여를 반환한다")
    void rollback_ok_recordsAndReturns() {
      // given
      givenValidDeduction();
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.OK, 5));

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).contains(5L);
      then(stockHistoryRecorder).should().record(mutation, StockChangeType.ROLLBACK);
      then(dropStockMetrics).should().register(dropId);
    }

    @Test
    @DisplayName("롤백 원장이 이미 커밋됐으면 캐시를 바꾸지 않고 현재 잔여를 반환한다")
    void rollback_committedHistory_returnsCurrentRemainingWithoutCacheMutation() {
      // given
      givenValidDeduction();
      givenCompletedHistory(StockChangeType.ROLLBACK);
      given(dropCacheRepository.findRemaining(List.of(dropId)))
          .willReturn(Map.of(dropId, 5L));

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).contains(5L);
      then(dropCacheRepository).should(never()).rollback(any());
      then(dropCacheRepository).should(never()).compensateRollback(any());
      then(stockHistoryRecorder).shouldHaveNoInteractions();
      then(dropStockMetrics).should().register(dropId);
    }

    @Test
    @DisplayName("롤백 원장이 이미 커밋됐고 라이브 캐시가 없으면 변경 없이 빈 결과를 반환한다")
    void rollback_committedHistoryWithoutCache_returnsEmptyWithoutMutation() {
      // given
      givenValidDeduction();
      givenCompletedHistory(StockChangeType.ROLLBACK);
      given(dropCacheRepository.findRemaining(List.of(dropId))).willReturn(Map.of());

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).isEmpty();
      then(dropCacheRepository).should(never()).rollback(any());
      then(dropCacheRepository).should(never()).compensateRollback(any());
      then(stockHistoryRecorder).shouldHaveNoInteractions();
      then(dropStockMetrics).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("멱등 키 만료 뒤 DB 충돌 원장과 요청 튜플이 다르면 캐시를 보상하고 거절한다")
    void deduct_insertConflictWithMismatchedHistory_compensatesAndThrowsConflict() {
      // given
      givenDeduct(StockCommandStatus.OK, 7);
      willThrow(new DataIntegrityViolationException("duplicate"))
          .given(stockHistoryRecorder)
          .record(mutation, StockChangeType.DEDUCT);
      given(dropCacheRepository.compensateDeduct(mutation)).willReturn(Optional.of(9L));
      givenHistory(
          StockChangeType.DEDUCT,
          Optional.of(deduction(UUID.randomUUID(), orderId, buyerId, 2)));

      // when & then
      assertThatThrownBy(() -> dropStockService.deduct(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_REQUEST_MISMATCH);
      then(dropCacheRepository).should().compensateDeduct(mutation);
      then(dropStockMetrics).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("DB 제약 충돌인데 커밋 원장이 없으면 원래 저장 예외를 전파한다")
    void deduct_insertConflictWithoutCommittedHistory_rethrowsPersistenceFailure() {
      // given
      DataIntegrityViolationException conflict =
          new DataIntegrityViolationException("unexpected constraint");
      givenDeduct(StockCommandStatus.OK, 7);
      willThrow(conflict)
          .given(stockHistoryRecorder)
          .record(mutation, StockChangeType.DEDUCT);
      given(dropCacheRepository.compensateDeduct(mutation)).willReturn(Optional.of(9L));
      givenHistory(StockChangeType.DEDUCT, Optional.empty());

      // when & then
      assertThatThrownBy(() -> dropStockService.deduct(command)).isSameAs(conflict);
      then(dropCacheRepository).should().compensateDeduct(mutation);
      then(dropStockMetrics).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("선행 요청의 롤백 원장이 아직 커밋되지 않았으면 DUPLICATE를 성공으로 반환하지 않는다")
    void rollback_duplicateBeforeHistoryCommit_throwsInProgress() {
      // given
      givenValidDeduction();
      givenHistory(StockChangeType.ROLLBACK, Optional.empty());
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.DUPLICATE, 5));

      // when & then
      assertThatThrownBy(() -> dropStockService.rollback(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_CHANGE_IN_PROGRESS);
      then(stockHistoryRecorder).shouldHaveNoInteractions();
      then(dropCacheRepository).should(never()).compensateRollback(any());
    }

    @Test
    @DisplayName("이미 커밋된 롤백 원장과 요청 튜플이 다르면 중복 성공으로 처리하지 않는다")
    void rollback_duplicateWithMismatchedHistory_throwsConflict() {
      // given
      givenValidDeduction();
      givenHistory(
          StockChangeType.ROLLBACK,
          Optional.of(history(dropId, orderId, UUID.randomUUID(), 2, StockChangeType.ROLLBACK)));

      // when & then
      assertThatThrownBy(() -> dropStockService.rollback(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_REQUEST_MISMATCH);
      then(dropCacheRepository).shouldHaveNoInteractions();
      then(stockHistoryRecorder).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("선행 차감 이력이 없으면 캐시를 변경하지 않고 롤백을 거절한다")
    void rollback_withoutDeduction_throwsBeforeCacheMutation() {
      // given
      given(
              stockHistoryRepository.findByOrderIdAndChangeTypeForUpdate(
                  orderId, StockChangeType.DEDUCT))
          .willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> dropStockService.rollback(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.ROLLBACK_NOT_ALLOWED);
      then(dropCacheRepository).shouldHaveNoInteractions();
      then(stockHistoryRecorder).shouldHaveNoInteractions();
    }

    @ParameterizedTest(name = "{0} 불일치")
    @EnumSource(DeductMismatch.class)
    @DisplayName("선행 차감 튜플이 다르면 캐시를 변경하지 않고 롤백을 거절한다")
    void rollback_mismatchedDeduction_throwsBeforeCacheMutation(DeductMismatch mismatch) {
      // given
      given(
              stockHistoryRepository.findByOrderIdAndChangeTypeForUpdate(
                  orderId, StockChangeType.DEDUCT))
          .willReturn(Optional.of(mismatchedDeduction(mismatch)));

      // when & then
      assertThatThrownBy(() -> dropStockService.rollback(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.ROLLBACK_NOT_ALLOWED);
      then(dropCacheRepository).shouldHaveNoInteractions();
      then(stockHistoryRecorder).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("롤백 이력이 이미 존재하면 캐시를 역연산하고 보상 후 실제 잔여를 반환한다")
    void rollback_insertConflict_compensatesAndReturnsActualRemaining() {
      // given
      givenValidDeduction();
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.OK, 5));
      willThrow(new DataIntegrityViolationException("duplicate"))
          .given(stockHistoryRecorder)
          .record(any(), eq(StockChangeType.ROLLBACK));
      given(dropCacheRepository.compensateRollback(mutation)).willReturn(Optional.of(3L));
      givenHistoryAfterPrecheck(
          StockChangeType.ROLLBACK,
          history(dropId, orderId, buyerId, 2, StockChangeType.ROLLBACK));

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).contains(3L);
      then(dropCacheRepository).should().compensateRollback(mutation);
      then(dropStockMetrics).should().register(dropId);
    }

    @Test
    @DisplayName("멱등 키 만료 뒤 롤백 DB 충돌 원장과 요청 튜플이 다르면 보상 후 거절한다")
    void rollback_insertConflictWithMismatchedHistory_compensatesAndThrowsConflict() {
      // given
      givenValidDeduction();
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.OK, 5));
      willThrow(new DataIntegrityViolationException("duplicate"))
          .given(stockHistoryRecorder)
          .record(mutation, StockChangeType.ROLLBACK);
      given(dropCacheRepository.compensateRollback(mutation)).willReturn(Optional.of(3L));
      givenHistoryAfterPrecheck(
          StockChangeType.ROLLBACK,
          history(dropId, orderId, UUID.randomUUID(), 2, StockChangeType.ROLLBACK));

      // when & then
      assertThatThrownBy(() -> dropStockService.rollback(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_REQUEST_MISMATCH);
      then(dropCacheRepository).should().compensateRollback(mutation);
      then(dropStockMetrics).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("캐시에 없고 종료된 드롭이면 복원하지 않는다(no-op)")
    void rollback_notCachedAndClosed_noOp() {
      // given
      givenValidDeduction();
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.NOT_CACHED, -1));
      given(dropRepository.findById(dropId))
          .willReturn(Optional.of(dropWithStatus(DropStatus.CLOSE)));

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).isEmpty();
      then(stockHistoryRecorder).should(never()).record(any(), any());
    }

    @Test
    @DisplayName("캐시에 없지만 진행 중인 드롭이면 이력에만 기록한다(복구 재워밍 대비)")
    void rollback_notCachedButRegistered_recordsLedger() {
      // given
      givenValidDeduction();
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.NOT_CACHED, -1));
      given(dropRepository.findById(dropId))
          .willReturn(Optional.of(dropWithStatus(DropStatus.REGISTERED)));

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).isEmpty();
      then(stockHistoryRecorder).should().record(mutation, StockChangeType.ROLLBACK);
    }
  }

  private enum DeductMismatch {
    ORDER_ID,
    DROP_ID,
    BUYER_ID,
    QUANTITY
  }

  private void givenDeduct(StockCommandStatus status, long remaining) {
    given(dropCacheRepository.deduct(eq(mutation), any(Instant.class)))
        .willReturn(new StockCommandResult(status, remaining));
  }

  private void givenValidDeduction() {
    given(
            stockHistoryRepository.findByOrderIdAndChangeTypeForUpdate(
                orderId, StockChangeType.DEDUCT))
        .willReturn(Optional.of(history(dropId, orderId, buyerId, 2, StockChangeType.DEDUCT)));
  }

  private void givenCompletedHistory(StockChangeType changeType) {
    givenHistory(
        changeType,
        Optional.of(history(dropId, orderId, buyerId, 2, changeType)));
  }

  private void givenHistory(StockChangeType changeType, Optional<StockHistory> history) {
    given(stockHistoryRepository.findByOrderIdAndChangeType(orderId, changeType))
        .willReturn(history);
  }

  private void givenHistoryAfterPrecheck(StockChangeType changeType, StockHistory history) {
    given(stockHistoryRepository.findByOrderIdAndChangeType(orderId, changeType))
        .willReturn(Optional.empty(), Optional.of(history));
  }

  private StockHistory mismatchedDeduction(DeductMismatch mismatch) {
    return deduction(
        mismatch == DeductMismatch.DROP_ID ? UUID.randomUUID() : dropId,
        mismatch == DeductMismatch.ORDER_ID ? UUID.randomUUID() : orderId,
        mismatch == DeductMismatch.BUYER_ID ? UUID.randomUUID() : buyerId,
        mismatch == DeductMismatch.QUANTITY ? 3 : 2);
  }

  private StockHistory deduction(UUID dropId, UUID orderId, UUID buyerId, int quantity) {
    return history(dropId, orderId, buyerId, quantity, StockChangeType.DEDUCT);
  }

  private StockHistory history(
      UUID dropId,
      UUID orderId,
      UUID buyerId,
      int quantity,
      StockChangeType changeType) {
    return StockHistory.record()
        .dropId(dropId)
        .orderId(orderId)
        .buyerId(buyerId)
        .changeType(changeType)
        .quantity(quantity)
        .build();
  }

  private Drop dropWithStatus(DropStatus status) {
    Drop drop =
        Drop.schedule()
            .product(null)
            .dropPrice(10_000L)
            .totalQuantity(100)
            .openAt(Instant.parse("2026-07-01T00:00:00Z"))
            .build();
    if (status == DropStatus.CLOSE) {
      drop.close();
    }
    return drop;
  }
}
