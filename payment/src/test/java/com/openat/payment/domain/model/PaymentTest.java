package com.openat.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

// 프레임워크 의존 없는 순수 도메인 테스트(7-12 plan WS-C) — 팩토리 불변식·전이표·refundableAmount 검증.
class PaymentTest {

  private final UUID orderId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();

  @Test
  void pendingPg는_PAYMENT_PENDING_상태로_생성된다() {
    Payment payment = Payment.pendingPg(orderId, memberId, 10_000L, "TOSS", "idem-1", "hash-1");

    assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAYMENT_PENDING);
    assertThat(payment.getMethod()).isEqualTo(Payment.Method.PG);
    assertThat(payment.getPgProvider()).isEqualTo("TOSS");
  }

  @Test
  void approvedWallet은_APPROVED_상태로_approvedAt이_채워져_생성된다() {
    Payment payment = Payment.approvedWallet(orderId, memberId, 10_000L, "idem-1", "hash-1");

    assertThat(payment.getStatus()).isEqualTo(Payment.Status.APPROVED);
    assertThat(payment.getMethod()).isEqualTo(Payment.Method.WALLET);
    assertThat(payment.getApprovedAt()).isNotNull();
  }

  @Test
  void reserveForConfirm은_PAYMENT_PENDING_상태로_pgPaymentKey와_파생_idempotencyKey를_채워_생성된다() {
    Payment payment =
        Payment.reserveForConfirm(orderId, memberId, 10_000L, "toss-payment-key", "hash-1");

    assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAYMENT_PENDING);
    assertThat(payment.getMethod()).isEqualTo(Payment.Method.PG);
    assertThat(payment.getPgProvider()).isEqualTo("TOSS");
    assertThat(payment.getPgPaymentKey()).isEqualTo("toss-payment-key");
    assertThat(payment.getPgPaymentKeyHash()).isEqualTo("hash-1");
    assertThat(payment.getIdempotencyKey()).isEqualTo("pay-key:hash-1");
  }

  @Test
  void 금액이_0이거나_음수이거나_null이면_생성시점에_예외가_발생한다() {
    assertThatThrownBy(() -> Payment.pendingPg(orderId, memberId, 0L, "TOSS", "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Payment.pendingPg(orderId, memberId, -1L, "TOSS", "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Payment.pendingPg(orderId, memberId, null, "TOSS", "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Payment.approvedWallet(orderId, memberId, 0L, "idem-1", "hash-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refundableAmount는_amount에서_refundedAmount를_뺀_값이다() {
    Payment payment =
        Payment.builder()
            .amount(10_000L)
            .refundedAmount(3_000L)
            .status(Payment.Status.APPROVED)
            .build();

    assertThat(payment.refundableAmount()).isEqualTo(7_000L);
  }

  @Test
  void isFinalized는_PENDING과_PAYMENT_PENDING만_false다() {
    assertThat(status(Payment.Status.PENDING).isFinalized()).isFalse();
    assertThat(status(Payment.Status.PAYMENT_PENDING).isFinalized()).isFalse();
    assertThat(status(Payment.Status.APPROVED).isFinalized()).isTrue();
    assertThat(status(Payment.Status.FAILED).isFinalized()).isTrue();
    assertThat(status(Payment.Status.CANCELED).isFinalized()).isTrue();
    assertThat(status(Payment.Status.REFUNDED).isFinalized()).isTrue();
    assertThat(status(Payment.Status.PARTIALLY_REFUNDED).isFinalized()).isTrue();
  }

  @Test
  void PAYMENT_PENDING에서_APPROVED_FAILED_CANCELED로만_전이할_수_있다() {
    assertThat(Payment.Status.PAYMENT_PENDING.canTransitionTo(Payment.Status.APPROVED)).isTrue();
    assertThat(Payment.Status.PAYMENT_PENDING.canTransitionTo(Payment.Status.FAILED)).isTrue();
    assertThat(Payment.Status.PAYMENT_PENDING.canTransitionTo(Payment.Status.CANCELED)).isTrue();
    assertThat(Payment.Status.PAYMENT_PENDING.canTransitionTo(Payment.Status.PAYMENT_PENDING))
        .isFalse();
    assertThat(Payment.Status.PAYMENT_PENDING.canTransitionTo(Payment.Status.REFUNDED)).isFalse();
  }

  @Test
  void 종결상태에서는_어디로도_전이할_수_없다() {
    for (Payment.Status terminal :
        new Payment.Status[] {
          Payment.Status.FAILED, Payment.Status.CANCELED, Payment.Status.REFUNDED
        }) {
      for (Payment.Status next : Payment.Status.values()) {
        assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
      }
    }
  }

  private Payment status(Payment.Status status) {
    return Payment.builder().status(status).build();
  }
}
