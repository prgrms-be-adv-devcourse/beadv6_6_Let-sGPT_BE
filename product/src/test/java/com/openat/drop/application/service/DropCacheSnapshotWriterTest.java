package com.openat.drop.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.repository.BuyerPurchase;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropCacheState;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 캐시 원장 스냅샷 작성")
class DropCacheSnapshotWriterTest {

  @InjectMocks private DropCacheSnapshotWriter snapshotWriter;
  @Mock private DropRepository dropRepository;
  @Mock private StockHistoryRepository stockHistoryRepository;
  @Mock private DropCacheRepository dropCacheRepository;

  private final UUID dropId = UUID.randomUUID();
  private final UUID recoveryOwner = UUID.randomUUID();

  @Test
  @DisplayName("드롭이 없으면 캐시를 적재하지 않는다")
  void warm_dropNotFound_doesNotWarm() {
    // given
    given(dropRepository.findByIdForUpdate(dropId)).willReturn(Optional.empty());

    // when
    snapshotWriter.write(dropId, recoveryOwner);

    // then
    then(dropCacheRepository).should(never()).warm(any(), any());
    then(stockHistoryRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("종료된 드롭이면 캐시를 적재하지 않는다")
  void warm_closedDrop_doesNotWarm() {
    // given
    Drop drop = drop(100);
    drop.close();
    given(dropRepository.findByIdForUpdate(dropId)).willReturn(Optional.of(drop));

    // when
    snapshotWriter.write(dropId, recoveryOwner);

    // then
    then(dropCacheRepository).should(never()).warm(any(), any());
    then(stockHistoryRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("진행 중인 드롭은 총 수량에 이력 합을 더한 잔여로 적재하고 순구매 0 이하 구매자는 카운터에서 제외한다")
  void warm_activeDrop_warmsWithLedgerAggregate() {
    // given
    int totalQuantity = 100;
    long ledgerSum = -30;
    Drop drop = drop(totalQuantity);
    UUID activeBuyer = UUID.randomUUID();
    UUID fullyRolledBackBuyer = UUID.randomUUID();
    UUID negativeNetBuyer = UUID.randomUUID();
    given(dropRepository.findByIdForUpdate(dropId)).willReturn(Optional.of(drop));
    given(stockHistoryRepository.sumQuantityDeltaByDropId(dropId)).willReturn(ledgerSum);
    given(stockHistoryRepository.sumNetQuantityByBuyer(dropId))
        .willReturn(
            List.of(
                new BuyerPurchase(activeBuyer, 3),
                new BuyerPurchase(fullyRolledBackBuyer, 0),
                new BuyerPurchase(negativeNetBuyer, -1)));

    // when
    snapshotWriter.write(dropId, recoveryOwner);

    // then
    ArgumentCaptor<DropCacheState> stateCaptor = ArgumentCaptor.forClass(DropCacheState.class);
    then(dropCacheRepository).should().warm(stateCaptor.capture(), eq(recoveryOwner));
    DropCacheState state = stateCaptor.getValue();
    assertThat(state.dropId()).isEqualTo(dropId);
    assertThat(state.remaining()).isEqualTo(totalQuantity + ledgerSum);
    assertThat(state.buyers()).containsOnly(entry(activeBuyer, 3L));
    assertThat(state.openAt()).isEqualTo(drop.getOpenAt());
    assertThat(state.closeAt()).isEqualTo(drop.getCloseAt());
    assertThat(state.limitPerUser()).isEqualTo(2);
  }

  private Drop drop(int totalQuantity) {
    return Drop.schedule()
        .product(null)
        .dropPrice(10_000L)
        .totalQuantity(totalQuantity)
        .limitPerUser(2)
        .openAt(Instant.parse("2026-07-01T00:00:00Z"))
        .closeAt(Instant.parse("2026-07-08T00:00:00Z"))
        .build();
  }
}
