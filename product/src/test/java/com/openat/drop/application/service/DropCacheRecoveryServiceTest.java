package com.openat.drop.application.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 캐시 롤백 복구")
class DropCacheRecoveryServiceTest {

  @InjectMocks private DropCacheRecoveryService recoveryService;
  @Mock private DropRepository dropRepository;
  @Mock private DropCacheRepository dropCacheRepository;

  @Test
  @DisplayName("DB에 등록 상태로 남은 드롭의 원래 종료 시각을 복구한다")
  void restoreCloseAt_registeredDrop_restoresDbValue() {
    UUID dropId = UUID.randomUUID();
    Instant closeAt = Instant.parse("2026-08-13T00:00:00Z");
    Drop drop = drop(closeAt);
    given(dropRepository.findByIdForUpdate(dropId)).willReturn(Optional.of(drop));

    recoveryService.restoreCloseAt(dropId);

    then(dropCacheRepository).should().restoreCloseAt(dropId, closeAt);
  }

  @Test
  @DisplayName("다른 종료가 이미 커밋된 드롭은 캐시를 다시 열지 않는다")
  void restoreCloseAt_closedDrop_doesNotRestore() {
    UUID dropId = UUID.randomUUID();
    Drop drop = drop(null);
    drop.close();
    given(dropRepository.findByIdForUpdate(dropId)).willReturn(Optional.of(drop));

    recoveryService.restoreCloseAt(dropId);

    then(dropCacheRepository).should(never()).restoreCloseAt(dropId, null);
  }

  private Drop drop(Instant closeAt) {
    return Drop.schedule()
        .product(null)
        .dropPrice(10_000L)
        .totalQuantity(10)
        .openAt(Instant.parse("2026-08-12T00:00:00Z"))
        .closeAt(closeAt)
        .build();
  }
}
