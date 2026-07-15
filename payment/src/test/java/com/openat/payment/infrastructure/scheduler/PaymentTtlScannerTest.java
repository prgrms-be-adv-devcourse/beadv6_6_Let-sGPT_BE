package com.openat.payment.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.service.PaymentFinalizer;
import com.openat.payment.application.service.WalletChargeFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletChargeRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// TTL 스캐너 단발화(D9, Q101 확정, 7-13 plan §6.5 WS-H) — 조기 폴링 제거 후 마지노선 하나로만 확정하는지 검증.
class PaymentTtlScannerTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletChargeRepository walletChargeRepository = mock(WalletChargeRepository.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final PaymentFinalizer finalizer = mock(PaymentFinalizer.class);
  private final WalletChargeFinalizer chargeFinalizer = mock(WalletChargeFinalizer.class);

  private PaymentTtlScanner scanner;

  @BeforeEach
  void setUp() {
    scanner =
        new PaymentTtlScanner(
            paymentRepository, walletChargeRepository, tossPaymentClient, finalizer, chargeFinalizer);
    ReflectionTestUtils.setField(scanner, "finalizeDeadlineMinutes", 8L);
    ReflectionTestUtils.setField(scanner, "pendingTimeoutMinutes", 10L);
    ReflectionTestUtils.setField(scanner, "nullKeyGraceMinutes", 0L);
    when(walletChargeRepository.findStalePending(any())).thenReturn(List.of());
  }

  private Payment paymentWithKeyCreatedAt(LocalDateTime createdAt) {
    return Payment.builder()
        .id(UUID.randomUUID())
        .pgPaymentKey("toss-payment-key")
        .status(Payment.Status.PAYMENT_PENDING)
        .createdAt(createdAt)
        .build();
  }

  @Test
  void 마지노선_전에는_조회하지_않는다() {
    Payment youngPending = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(5));
    when(paymentRepository.findStalePending(any())).thenReturn(List.of(youngPending));

    scanner.scan();

    verify(tossPaymentClient, never()).queryPaymentStatus(any());
    verify(finalizer, never()).finalizePending(any(), any(), any(), any());
  }

  @Test
  void 마지노선_조회가_에러면_즉시_FORCED_TIMEOUT으로_종결한다() {
    Payment overdue = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any())).thenReturn(List.of(overdue));
    when(tossPaymentClient.queryPaymentStatus("toss-payment-key"))
        .thenThrow(new RuntimeException("PG 조회 실패"));

    scanner.scan();

    verify(finalizer).finalizePending(overdue.getId(), Payment.Status.FAILED, null, "FORCED_TIMEOUT");
  }
}
