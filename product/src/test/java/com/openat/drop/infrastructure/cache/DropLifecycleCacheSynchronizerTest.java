package com.openat.drop.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.service.DropCacheRecoveryService;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.repository.DropCacheRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 생명주기 캐시 동기화")
class DropLifecycleCacheSynchronizerTest {

  @InjectMocks private DropLifecycleCacheSynchronizer synchronizer;
  @Mock private DropCacheRepository dropCacheRepository;
  @Mock private DropCacheRecoveryService dropCacheRecoveryService;
  @Mock private DropCacheWarmer dropCacheWarmer;

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
  }

  @Test
  @DisplayName("캐시 차단 시점에 오픈된 드롭이면 DB 삭제를 중단한다")
  void evictBeforeCommit_openedAtFence_throwsConflict() {
    UUID dropId = UUID.randomUUID();
    given(dropCacheRepository.evictBeforeOpen(dropId)).willReturn(false);

    assertThatThrownBy(
            () -> synchronizer.evictBeforeCommit(new DropDeletedEvent(dropId, true)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.OPEN_EXISTS);
  }

  @Test
  @DisplayName("종료 트랜잭션 롤백 뒤 DB의 판매 종료 시각만 복구한다")
  void restoreAfterCloseRollback_restoresCloseAt() {
    UUID dropId = UUID.randomUUID();

    synchronizer.restoreAfterCloseRollback(new DropClosedEvent(dropId));

    then(dropCacheRecoveryService).should().restoreCloseAt(dropId);
  }

  @Test
  @DisplayName("오픈 전 삭제 트랜잭션 롤백 뒤 원장 스냅샷으로 재워밍한다")
  void restoreAfterDeleteRollback_warmsEvictedDrop() {
    UUID dropId = UUID.randomUUID();

    synchronizer.restoreAfterDeleteRollback(new DropDeletedEvent(dropId, true));

    then(dropCacheWarmer).should().warm(dropId);
  }

  @Test
  @DisplayName("롤백 복구 실패는 원래 트랜잭션 예외를 덮지 않는다")
  void restoreAfterRollback_recoveryFailureIsContained() {
    UUID dropId = UUID.randomUUID();
    willThrow(new IllegalStateException("redis unavailable"))
        .given(dropCacheRecoveryService)
        .restoreCloseAt(dropId);
    willThrow(new IllegalStateException("redis unavailable")).given(dropCacheWarmer).warm(dropId);

    synchronizer.restoreAfterCloseRollback(new DropClosedEvent(dropId));
    synchronizer.restoreAfterDeleteRollback(new DropDeletedEvent(dropId, true));

    then(dropCacheRecoveryService).should().restoreCloseAt(dropId);
    then(dropCacheWarmer).should().warm(dropId);
  }
}
