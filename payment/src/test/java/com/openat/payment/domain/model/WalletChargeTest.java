package com.openat.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

// 프레임워크 의존 없는 순수 도메인 테스트(7-12 plan WS-C).
class WalletChargeTest {

  private final UUID memberId = UUID.randomUUID();

  @Test
  void pendingPg는_PENDING_상태로_생성된다() {
    WalletCharge charge = WalletCharge.pendingPg(memberId, 10_000L, "idem-1", "hash-1");

    assertThat(charge.getStatus()).isEqualTo(WalletCharge.Status.PENDING);
    assertThat(charge.getMethod()).isEqualTo(WalletCharge.Method.PG);
  }

  @Test
  void approvedMock은_APPROVED_상태로_즉시_생성된다() {
    WalletCharge charge = WalletCharge.approvedMock(memberId, 10_000L, "idem-1", "hash-1");

    assertThat(charge.getStatus()).isEqualTo(WalletCharge.Status.APPROVED);
    assertThat(charge.getMethod()).isEqualTo(WalletCharge.Method.MOCK);
  }

  @Test
  void 금액이_0이거나_음수이거나_null이면_생성시점에_예외가_발생한다() {
    assertThatThrownBy(() -> WalletCharge.pendingPg(memberId, 0L, "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WalletCharge.approvedMock(memberId, -1L, "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WalletCharge.pendingPg(memberId, null, "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void PENDING에서_APPROVED_FAILED로만_전이할_수_있다() {
    assertThat(WalletCharge.Status.PENDING.canTransitionTo(WalletCharge.Status.APPROVED)).isTrue();
    assertThat(WalletCharge.Status.PENDING.canTransitionTo(WalletCharge.Status.FAILED)).isTrue();
    assertThat(WalletCharge.Status.PENDING.canTransitionTo(WalletCharge.Status.PENDING)).isFalse();
  }

  @Test
  void 종결상태에서는_어디로도_전이할_수_없다() {
    assertThat(WalletCharge.Status.APPROVED.canTransitionTo(WalletCharge.Status.FAILED)).isFalse();
    assertThat(WalletCharge.Status.FAILED.canTransitionTo(WalletCharge.Status.APPROVED)).isFalse();
  }
}
