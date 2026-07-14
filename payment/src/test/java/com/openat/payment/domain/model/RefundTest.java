package com.openat.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

// 프레임워크 의존 없는 순수 도메인 테스트(7-12 plan WS-C).
class RefundTest {

  private final UUID paymentId = UUID.randomUUID();

  @Test
  void pending은_PENDING_상태로_생성된다() {
    Refund refund = Refund.pending(paymentId, 5_000L, "단순변심", "idem-1", "hash-1");

    assertThat(refund.getStatus()).isEqualTo(Refund.Status.PENDING);
    assertThat(refund.getPaymentId()).isEqualTo(paymentId);
    assertThat(refund.getAmount()).isEqualTo(5_000L);
  }

  @Test
  void 금액이_0이거나_음수이거나_null이면_생성시점에_예외가_발생한다() {
    assertThatThrownBy(() -> Refund.pending(paymentId, 0L, "reason", "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Refund.pending(paymentId, -1L, "reason", "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Refund.pending(paymentId, null, "reason", "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void PENDING에서_COMPLETE_FAILED로만_전이할_수_있다() {
    assertThat(Refund.Status.PENDING.canTransitionTo(Refund.Status.COMPLETE)).isTrue();
    assertThat(Refund.Status.PENDING.canTransitionTo(Refund.Status.FAILED)).isTrue();
    assertThat(Refund.Status.PENDING.canTransitionTo(Refund.Status.PENDING)).isFalse();
  }

  @Test
  void 종결상태에서는_어디로도_전이할_수_없다() {
    assertThat(Refund.Status.COMPLETE.canTransitionTo(Refund.Status.FAILED)).isFalse();
    assertThat(Refund.Status.FAILED.canTransitionTo(Refund.Status.COMPLETE)).isFalse();
  }
}
