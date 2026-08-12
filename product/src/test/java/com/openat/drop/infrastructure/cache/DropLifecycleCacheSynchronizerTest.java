package com.openat.drop.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.service.DropCacheRecoveryService;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.infrastructure.schedule.DropScheduler;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 생명주기 캐시 동기화")
class DropLifecycleCacheSynchronizerTest {

  @InjectMocks private DropLifecycleCacheSynchronizer synchronizer;
  @Mock private DropCacheRepository dropCacheRepository;
  @Mock private DropCacheRecoveryService dropCacheRecoveryService;
  @Mock private DropScheduler dropScheduler;

  @BeforeEach
  void initSynchronization() {
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void clearSynchronization() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  @DisplayName("종료 커밋 전 캐시에서 신규 선점을 차단한다")
  void closeBeforeCommit_marksCacheClosed() {
    UUID dropId = UUID.randomUUID();

    synchronizer.closeBeforeCommit(new DropClosedEvent(dropId));

    then(dropCacheRepository).should().markClosed(dropId);
  }

  @Test
  @DisplayName("오픈 전 삭제만 커밋 전 캐시를 제거한다")
  void evictBeforeCommit_evictsOnlyPreOpenDrop() {
    UUID preOpenDropId = UUID.randomUUID();
    UUID drainDropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(preOpenDropId)).willReturn(true);

    synchronizer.evictBeforeCommit(new DropDeletedEvent(preOpenDropId, true));
    synchronizer.evictBeforeCommit(new DropDeletedEvent(drainDropId, false));

    then(dropCacheRepository).should().evictBeforeOpen(preOpenDropId);
    then(dropCacheRepository).should(never()).evictBeforeOpen(drainDropId);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    then(dropScheduler).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("캐시 차단 시점에 오픈된 드롭이면 DB 삭제를 중단한다")
  void evictBeforeCommit_openedAtFence_throwsConflict() {
    UUID dropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(false);

    assertThatThrownBy(() -> synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.OPEN_EXISTS);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    then(dropScheduler).shouldHaveNoInteractions();
    then(dropCacheRecoveryService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("종료 트랜잭션 롤백 뒤 DB의 판매 종료 시각만 복구한다")
  void restoreAfterCloseRollback_restoresCloseAt() {
    UUID dropId = UUID.randomUUID();

    synchronizer.restoreAfterCloseRollback(new DropClosedEvent(dropId));

    then(dropCacheRecoveryService).should().restoreCloseAt(dropId);
  }

  @Test
  @DisplayName("캐시를 제거한 삭제의 롤백은 현재 메타로 재시도 가능한 원장 복구를 예약한다")
  void deleteRollback_restoresCacheFromLedgerAfterRemoval() {
    UUID dropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(true);
    Drop drop = givenActiveDrop(dropId);

    synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true));
    then(dropScheduler).shouldHaveNoInteractions();
    then(dropCacheRecoveryService).shouldHaveNoInteractions();
    afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    then(dropCacheRecoveryService).should().findActiveDrop(dropId);
    then(dropScheduler).should().recoverAfterRollback(dropId, drop.getCloseAt());
  }

  @Test
  @DisplayName("삭제 커밋 성공과 알 수 없는 완료 상태는 캐시를 복구하지 않는다")
  void deleteCompleted_withoutRollback_doesNotRecover() {
    UUID dropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(true);

    synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true));
    afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

    then(dropScheduler).shouldHaveNoInteractions();
    then(dropCacheRecoveryService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("drain 캐시 삭제는 제거도 롤백 복구 등록도 하지 않는다")
  void deleteDrain_doesNotRegisterRecovery() {
    synchronizer.evictBeforeCommit(new DropDeletedEvent(UUID.randomUUID(), false));

    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    then(dropCacheRepository).shouldHaveNoInteractions();
    then(dropScheduler).shouldHaveNoInteractions();
    then(dropCacheRecoveryService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("제거 응답 유실은 원래 예외를 보존하고 롤백 때만 원장 복구를 요청한다")
  void uncertainRemoval_registersRollbackRecovery() {
    UUID dropId = UUID.randomUUID();
    IllegalStateException failure = new IllegalStateException("eviction response lost");
    given(dropCacheRepository.evictBeforeOpen(dropId)).willThrow(failure);
    Drop drop = givenActiveDrop(dropId);

    assertThatThrownBy(() -> synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true)))
        .isSameAs(failure);

    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    then(dropScheduler).shouldHaveNoInteractions();
    afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    then(dropScheduler).should().recoverAfterRollback(dropId, drop.getCloseAt());
  }

  @Test
  @DisplayName("롤백 복구 실패는 원래 트랜잭션 예외를 덮지 않는다")
  void restoreAfterRollback_recoveryFailureIsContained() {
    UUID dropId = UUID.randomUUID();
    willThrow(new IllegalStateException("redis unavailable"))
        .given(dropCacheRecoveryService)
        .restoreCloseAt(dropId);
    Drop drop = givenActiveDrop(dropId);
    willThrow(new IllegalStateException("redis unavailable"))
        .given(dropScheduler)
        .recoverAfterRollback(dropId, drop.getCloseAt());
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(true);

    assertThatCode(() -> synchronizer.restoreAfterCloseRollback(new DropClosedEvent(dropId)))
        .doesNotThrowAnyException();
    synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true));
    assertThatCode(() -> afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
        .doesNotThrowAnyException();

    then(dropCacheRecoveryService).should().restoreCloseAt(dropId);
    then(dropScheduler).should().recoverAfterRollback(dropId, drop.getCloseAt());
  }

  @Test
  @DisplayName("현재 활성 드롭이 없으면 삭제 롤백 후 복구를 재예약하지 않는다")
  void deleteRollback_withoutActiveDrop_doesNotReschedule() {
    UUID dropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(true);
    given(dropCacheRecoveryService.findActiveDrop(dropId)).willReturn(Optional.empty());

    synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true));
    afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

    then(dropCacheRecoveryService).should().findActiveDrop(dropId);
    then(dropScheduler).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("삭제 롤백 뒤 메타 조회 실패도 원래 트랜잭션 예외를 덮지 않는다")
  void deleteRollback_metadataFailure_isContained() {
    UUID dropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(true);
    given(dropCacheRecoveryService.findActiveDrop(dropId))
        .willThrow(new IllegalStateException("database unavailable"));

    synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true));
    assertThatCode(() -> afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK))
        .doesNotThrowAnyException();

    then(dropScheduler).shouldHaveNoInteractions();
  }

  private Drop givenActiveDrop(UUID dropId) {
    Drop drop =
        Drop.schedule()
            .product(null)
            .dropPrice(10_000L)
            .totalQuantity(10)
            .openAt(Instant.parse("2026-08-12T00:00:00Z"))
            .closeAt(Instant.parse("2026-08-13T00:00:00Z"))
            .build();
    given(dropCacheRecoveryService.findActiveDrop(dropId)).willReturn(Optional.of(drop));
    return drop;
  }

  private void afterCompletion(int status) {
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCompletion(status));
  }
}
