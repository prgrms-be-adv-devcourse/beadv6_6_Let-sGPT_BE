package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.openat.common.exception.BusinessException;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.repository.DropRecoveryRepository;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 캐시 복구 조정")
class DropCacheWarmerTest {

  @Mock private DropRecoveryRepository recoveryRepository;
  @Mock private DropCacheSnapshotWriter snapshotWriter;

  private final UUID dropId = UUID.randomUUID();
  private final Duration lease = Duration.ofSeconds(30);
  private DropCacheWarmer warmer;

  @BeforeEach
  void setUp() {
    warmer = new DropCacheWarmer(recoveryRepository, snapshotWriter, lease);
  }

  @Test
  @DisplayName("원장 복구는 lease를 획득한 뒤 같은 owner로 스냅샷을 쓰고 해제한다")
  void warm_admitsBeforeReplacingSnapshot() {
    givenAdmission();

    warmer.warm(dropId);

    InOrder order = inOrder(recoveryRepository, snapshotWriter);
    ArgumentCaptor<UUID> owner = ArgumentCaptor.forClass(UUID.class);
    order.verify(recoveryRepository).beginRecovery(eq(dropId), owner.capture(), eq(lease));
    order.verify(snapshotWriter).write(dropId, owner.getValue());
    order.verify(recoveryRepository).completeRecovery(dropId, owner.getValue());
  }

  @Test
  @DisplayName("진행 중 변경으로 복구가 거절되면 SQL 스냅샷과 lease 해제를 실행하지 않는다")
  void warm_busy_doesNotOpenSnapshotOrReleaseAnotherOwner() {
    given(recoveryRepository.beginRecovery(eq(dropId), any(), eq(lease))).willReturn(false);

    assertThatThrownBy(() -> warmer.warm(dropId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_CHANGE_IN_PROGRESS);

    then(snapshotWriter).shouldHaveNoInteractions();
    then(recoveryRepository).should(never()).completeRecovery(any(), any());
  }

  @Test
  @DisplayName("lease 획득 응답이 불명확하면 스냅샷을 쓰거나 임의로 해제하지 않는다")
  void warm_admissionFailure_doesNotPublishOrRelease() {
    IllegalStateException failure = new IllegalStateException("admission response lost");
    given(recoveryRepository.beginRecovery(eq(dropId), any(), eq(lease))).willThrow(failure);

    assertThatThrownBy(() -> warmer.warm(dropId)).isSameAs(failure);

    then(snapshotWriter).shouldHaveNoInteractions();
    then(recoveryRepository).should(never()).completeRecovery(any(), any());
  }

  @Test
  @DisplayName("스냅샷 실패에도 획득한 lease는 해제하고 원래 예외를 전파한다")
  void warm_snapshotFailure_releasesOwnerAndPreservesFailure() {
    givenAdmission();
    IllegalStateException failure = new IllegalStateException("snapshot failed");
    willThrow(failure).given(snapshotWriter).write(eq(dropId), any());

    assertThatThrownBy(() -> warmer.warm(dropId)).isSameAs(failure);

    then(recoveryRepository).should().completeRecovery(eq(dropId), any());
  }

  @Test
  @DisplayName("lease 정리 실패는 이미 게시한 스냅샷의 성공을 실패로 바꾸지 않는다")
  void warm_cleanupFailure_preservesSuccess() {
    givenAdmission();
    willThrow(new IllegalStateException("cleanup failed"))
        .given(recoveryRepository)
        .completeRecovery(eq(dropId), any());

    assertThatCode(() -> warmer.warm(dropId)).doesNotThrowAnyException();

    then(snapshotWriter).should().write(eq(dropId), any());
  }

  @Test
  @DisplayName("lease 정리와 스냅샷이 모두 실패하면 스냅샷의 원래 예외를 보존한다")
  void warm_snapshotAndCleanupFail_preservesSnapshotFailure() {
    givenAdmission();
    IllegalStateException failure = new IllegalStateException("snapshot failed");
    willThrow(failure).given(snapshotWriter).write(eq(dropId), any());
    willThrow(new IllegalStateException("cleanup failed"))
        .given(recoveryRepository)
        .completeRecovery(eq(dropId), any());

    assertThatThrownBy(() -> warmer.warm(dropId)).isSameAs(failure);
  }

  @Test
  @DisplayName("매 복구 시도마다 owner를 새로 발급한다")
  void warm_repeatedRecovery_usesDistinctOwners() {
    givenAdmission();

    warmer.warm(dropId);
    warmer.warm(dropId);

    ArgumentCaptor<UUID> owners = ArgumentCaptor.forClass(UUID.class);
    then(recoveryRepository)
        .should(times(2))
        .beginRecovery(eq(dropId), owners.capture(), eq(lease));
    assertThat(owners.getAllValues()).hasSize(2).doesNotHaveDuplicates();
    for (UUID owner : owners.getAllValues()) {
      then(snapshotWriter).should().write(dropId, owner);
      then(recoveryRepository).should().completeRecovery(dropId, owner);
    }
  }

  private void givenAdmission() {
    given(recoveryRepository.beginRecovery(eq(dropId), any(), eq(lease))).willReturn(true);
  }
}
