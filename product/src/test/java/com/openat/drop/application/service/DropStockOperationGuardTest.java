package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.openat.common.exception.BusinessException;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.repository.DropRecoveryRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 재고 작업 복구 가드")
class DropStockOperationGuardTest {

  @Mock private DropRecoveryRepository dropRecoveryRepository;
  @InjectMocks private DropStockOperationGuard guard;

  private final UUID dropId = UUID.randomUUID();

  @Test
  @DisplayName("정상 결과는 같은 시도 토큰을 정리하고 그대로 반환한다")
  void successfulChange_completesSameAttemptAndReturnsResult() {
    givenAdmission();

    assertThat(guard.execute(dropId, () -> 7L)).isEqualTo(7L);

    ArgumentCaptor<UUID> attempt = ArgumentCaptor.forClass(UUID.class);
    then(dropRecoveryRepository).should().beginChange(eq(dropId), attempt.capture());
    then(dropRecoveryRepository).should().completeChange(dropId, attempt.getValue());
  }

  @Test
  @DisplayName("복구 lease 중에는 업무를 실행하거나 다른 작업의 마커를 정리하지 않는다")
  void recoveryInProgress_rejectsBeforeActionWithoutCleanup() {
    given(dropRecoveryRepository.beginChange(eq(dropId), any())).willReturn(false);
    Supplier<?> action = mock(Supplier.class);

    assertThatThrownBy(() -> guard.execute(dropId, action))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", DropErrorCode.STOCK_CHANGE_IN_PROGRESS);

    then(action).shouldHaveNoInteractions();
    then(dropRecoveryRepository).should(never()).completeChange(any(), any());
  }

  @Test
  @DisplayName("확정된 업무 거절은 마커를 정리하고 원래 예외를 반환한다")
  void businessRejection_completesAndPreservesException() {
    givenAdmission();
    BusinessException rejection = new BusinessException(DropErrorCode.SOLD_OUT);

    assertThatThrownBy(
            () ->
                guard.execute(
                    dropId,
                    () -> {
                      throw rejection;
                    }))
        .isSameAs(rejection);

    then(dropRecoveryRepository).should().completeChange(eq(dropId), any());
  }

  @Test
  @DisplayName("업무의 불명확한 런타임 실패는 마커를 남겨 복구를 차단한다")
  void uncertainRuntimeFailure_retainsMarkerAndPreservesException() {
    givenAdmission();
    IllegalStateException failure = new IllegalStateException("commit response lost");

    assertThatThrownBy(
            () ->
                guard.execute(
                    dropId,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    then(dropRecoveryRepository).should(never()).completeChange(any(), any());
  }

  @Test
  @DisplayName("업무 Error도 무조건 정리하지 않고 원래 오류를 전파한다")
  void uncertainError_retainsMarkerAndPreservesError() {
    givenAdmission();
    AssertionError failure = new AssertionError("interrupted operation");

    assertThatThrownBy(
            () ->
                guard.execute(
                    dropId,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    then(dropRecoveryRepository).should(never()).completeChange(any(), any());
  }

  @Test
  @DisplayName("입장 응답 실패는 실제 등록 여부를 모르므로 업무와 정리를 실행하지 않는다")
  void uncertainAdmission_doesNotRunActionOrRemoveMarker() {
    IllegalStateException failure = new IllegalStateException("Redis response lost");
    given(dropRecoveryRepository.beginChange(eq(dropId), any())).willThrow(failure);
    Supplier<?> action = mock(Supplier.class);

    assertThatThrownBy(() -> guard.execute(dropId, action)).isSameAs(failure);

    then(action).shouldHaveNoInteractions();
    then(dropRecoveryRepository).should(never()).completeChange(any(), any());
  }

  @Test
  @DisplayName("정리 Redis 실패는 이미 확정된 성공 결과를 바꾸지 않는다")
  void cleanupFailure_preservesSuccessfulResult() {
    givenAdmission();
    willThrow(new IllegalStateException("cleanup unavailable"))
        .given(dropRecoveryRepository)
        .completeChange(eq(dropId), any());

    assertThat(guard.execute(dropId, () -> 7L)).isEqualTo(7L);

    then(dropRecoveryRepository).should().completeChange(eq(dropId), any());
  }

  @Test
  @DisplayName("정리 Redis 실패는 확정된 업무 거절의 원래 예외를 가리지 않는다")
  void cleanupFailure_preservesBusinessRejection() {
    givenAdmission();
    willThrow(new IllegalStateException("cleanup unavailable"))
        .given(dropRecoveryRepository)
        .completeChange(eq(dropId), any());
    BusinessException rejection = new BusinessException(DropErrorCode.LIMIT_EXCEEDED);

    assertThatThrownBy(
            () ->
                guard.execute(
                    dropId,
                    () -> {
                      throw rejection;
                    }))
        .isSameAs(rejection);

    then(dropRecoveryRepository).should().completeChange(eq(dropId), any());
  }

  @Test
  @DisplayName("같은 드롭의 두 동시 작업은 서로 다른 시도 토큰으로 각각 정리한다")
  void concurrentChanges_haveIndependentAttemptTokens() throws Exception {
    givenAdmission();
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Supplier<Long> action =
        () -> {
          started.countDown();
          await(release);
          return 7L;
        };

    try {
      Future<Long> first = executor.submit(() -> guard.execute(dropId, action));
      Future<Long> second = executor.submit(() -> guard.execute(dropId, action));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      then(dropRecoveryRepository).should(never()).completeChange(any(), any());
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(7L);
      assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(7L);

      ArgumentCaptor<UUID> admitted = ArgumentCaptor.forClass(UUID.class);
      ArgumentCaptor<UUID> completed = ArgumentCaptor.forClass(UUID.class);
      then(dropRecoveryRepository).should(times(2)).beginChange(eq(dropId), admitted.capture());
      then(dropRecoveryRepository).should(times(2)).completeChange(eq(dropId), completed.capture());
      assertThat(admitted.getAllValues()).doesNotHaveDuplicates().hasSize(2);
      assertThat(completed.getAllValues())
          .containsExactlyInAnyOrderElementsOf(admitted.getAllValues());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private void givenAdmission() {
    given(dropRecoveryRepository.beginChange(eq(dropId), any())).willReturn(true);
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test operation wait timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test operation interrupted", interrupted);
    }
  }
}
