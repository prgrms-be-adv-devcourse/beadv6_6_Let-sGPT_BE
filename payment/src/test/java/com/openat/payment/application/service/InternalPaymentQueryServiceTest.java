package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.InternalPaymentStatusResult;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 순수 Mockito 단위테스트 — 성사분 우선 선택·최신 폴백·404 규칙 검증.
class InternalPaymentQueryServiceTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);

  private InternalPaymentQueryService service;

  private UUID orderId;

  @BeforeEach
  void setUp() {
    service = new InternalPaymentQueryService(paymentRepository);
    orderId = UUID.randomUUID();
  }

  private Payment payment(UUID id, Payment.Status status, long amount) {
    return Payment.builder()
        .id(id)
        .orderId(orderId)
        .memberId(UUID.randomUUID())
        .amount(amount)
        .method(Payment.Method.PG)
        .status(status)
        .refundedAmount(0L)
        .build();
  }

  @Test
  void 성사분_APPROVED을_우선_선택해_상태를_반환한다() {
    UUID paymentId = UUID.randomUUID();
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
        .thenReturn(Optional.of(payment(paymentId, Payment.Status.APPROVED, 10_000L)));

    InternalPaymentStatusResult result = service.getByOrderId(orderId);

    assertThat(result.paymentId()).isEqualTo(paymentId);
    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.amount()).isEqualTo(10_000L);
  }

  @Test
  void 성사분이_없으면_최신_1건으로_폴백한다() {
    UUID paymentId = UUID.randomUUID();
    when(paymentRepository.findByOrderId(orderId))
        .thenReturn(Optional.of(payment(paymentId, Payment.Status.FAILED, 10_000L)));

    InternalPaymentStatusResult result = service.getByOrderId(orderId);

    assertThat(result.paymentId()).isEqualTo(paymentId);
    assertThat(result.status()).isEqualTo("FAILED");
  }

  @Test
  void 결제가_전혀_없으면_NOT_FOUND_예외가_발생한다() {
    assertThatThrownBy(() -> service.getByOrderId(orderId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.NOT_FOUND);
  }
}
