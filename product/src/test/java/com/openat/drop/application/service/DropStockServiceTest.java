package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropStockCommand;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.model.StockCommandStatus;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockCommandResult;
import com.openat.drop.domain.repository.StockMutation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  @Mock private DropRepository dropRepository;

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
    }

    @Test
    @DisplayName("DUPLICATE면 이력을 기록하지 않고 잔여를 반환한다")
    void deduct_duplicate_returnsWithoutRecording() {
      // given
      givenDeduct(StockCommandStatus.DUPLICATE, 7);

      // when
      long remaining = dropStockService.deduct(command);

      // then
      assertThat(remaining).isEqualTo(7);
      then(stockHistoryRecorder).should(never()).record(any(), any());
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

      // when
      long remaining = dropStockService.deduct(command);

      // then
      assertThat(remaining).isEqualTo(7);
      then(dropCacheRepository).should().compensateDeduct(mutation);
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
      given(dropCacheRepository.rollback(mutation))
          .willReturn(new StockCommandResult(StockCommandStatus.OK, 5));

      // when
      Optional<Long> remaining = dropStockService.rollback(command);

      // then
      assertThat(remaining).contains(5L);
      then(stockHistoryRecorder).should().record(mutation, StockChangeType.ROLLBACK);
    }

    @Test
    @DisplayName("캐시에 없고 종료된 드롭이면 복원하지 않는다(no-op)")
    void rollback_notCachedAndClosed_noOp() {
      // given
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

  private void givenDeduct(StockCommandStatus status, long remaining) {
    given(dropCacheRepository.deduct(eq(mutation), any(Instant.class)))
        .willReturn(new StockCommandResult(status, remaining));
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
