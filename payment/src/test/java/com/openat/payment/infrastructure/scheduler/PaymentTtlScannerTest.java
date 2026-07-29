package com.openat.payment.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.PaymentFinalizer;
import com.openat.payment.application.service.RefundFinalizer;
import com.openat.payment.application.service.WalletChargeFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletChargeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// TTL 스캐너 단발화(D9, Q101 확정, 7-13 plan §6.5 WS-H) — 조기 폴링 제거 후 마지노선 하나로만 확정하는지 검증.
class PaymentTtlScannerTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletChargeRepository walletChargeRepository = mock(WalletChargeRepository.class);
  private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final PaymentFinalizer finalizer = mock(PaymentFinalizer.class);
  private final WalletChargeFinalizer chargeFinalizer = mock(WalletChargeFinalizer.class);
  private final RefundFinalizer refundFinalizer = mock(RefundFinalizer.class);

  private PaymentTtlScanner scanner;

  @BeforeEach
  void setUp() {
    scanner =
        new PaymentTtlScanner(
            paymentRepository,
            walletChargeRepository,
            refundRepository,
            tossPaymentClient,
            finalizer,
            chargeFinalizer,
            refundFinalizer,
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(scanner, "finalizeDeadlineMinutes", 8L);
    ReflectionTestUtils.setField(scanner, "pendingTimeoutMinutes", 10L);
    ReflectionTestUtils.setField(scanner, "nullKeyGraceMinutes", 0L);
    ReflectionTestUtils.setField(scanner, "maxPerCycle", 100);
    ReflectionTestUtils.setField(scanner, "cycleBudgetSeconds", 30L);
    when(walletChargeRepository.findStalePending(any(), anyInt())).thenReturn(List.of());
    when(refundRepository.findStalePending(any(), anyInt())).thenReturn(List.of());
    when(paymentRepository.findStalePending(any(), anyInt())).thenReturn(List.of());
  }

  private Payment paymentWithKeyCreatedAt(LocalDateTime createdAt) {
    return paymentWithKeyCreatedAt("toss-payment-key", createdAt);
  }

  private Payment paymentWithKeyCreatedAt(String pgPaymentKey, LocalDateTime createdAt) {
    return Payment.builder()
        .id(UUID.randomUUID())
        .pgPaymentKey(pgPaymentKey)
        .status(Payment.Status.PAYMENT_PENDING)
        .createdAt(createdAt)
        .build();
  }

  @Test
  void 마지노선_전에는_조회하지_않는다() {
    Payment youngPending = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(5));
    when(paymentRepository.findStalePending(any(), anyInt())).thenReturn(List.of(youngPending));

    scanner.scan();

    verify(tossPaymentClient, never()).queryPaymentStatus(any());
    verify(finalizer, never()).finalizePending(any(), any(), any(), any());
  }

  @Test
  void 마지노선_조회가_에러면_즉시_FORCED_TIMEOUT으로_종결한다() {
    Payment overdue = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any(), anyInt())).thenReturn(List.of(overdue));
    when(tossPaymentClient.queryPaymentStatus("toss-payment-key"))
        .thenThrow(new RuntimeException("PG 조회 실패"));

    scanner.scan();

    verify(finalizer).finalizePending(overdue.getId(), Payment.Status.FAILED, null, "FORCED_TIMEOUT");
  }

  private Refund refundPendingCreatedAt(UUID paymentId, LocalDateTime createdAt) {
    return Refund.builder()
        .id(UUID.randomUUID())
        .paymentId(paymentId)
        .amount(3_000L)
        .status(Refund.Status.PENDING)
        .createdAt(createdAt)
        .build();
  }

  @Test
  void 마지노선_지난_PENDING_환불은_토스_조회_결과로_확정한다() {
    UUID paymentId = UUID.randomUUID();
    Payment payment = Payment.builder().id(paymentId).pgPaymentKey("toss-payment-key").build();
    Refund overdue = refundPendingCreatedAt(paymentId, LocalDateTime.now().minusMinutes(9));
    when(refundRepository.findStalePending(any(), anyInt())).thenReturn(List.of(overdue));
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(tossPaymentClient.queryRefundStatus("toss-payment-key", null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "toss-refund-1"));

    scanner.scan();

    verify(refundFinalizer).complete(overdue.getId(), payment, "toss-refund-1");
  }

  @Test
  void 마지노선_전_PENDING_환불은_조회하지_않는다() {
    UUID paymentId = UUID.randomUUID();
    Refund young = refundPendingCreatedAt(paymentId, LocalDateTime.now().minusMinutes(5));
    when(refundRepository.findStalePending(any(), anyInt())).thenReturn(List.of(young));

    scanner.scan();

    verify(tossPaymentClient, never()).queryRefundStatus(any(), any(), any());
    verify(refundFinalizer, never()).complete(any(), any(), any());
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  @Test
  void PENDING_환불_조회결과가_NOT_FOUND면_강제_종결하지_않고_PENDING을_유지한다() {
    UUID paymentId = UUID.randomUUID();
    Payment payment = Payment.builder().id(paymentId).pgPaymentKey("toss-payment-key").build();
    Refund overdue = refundPendingCreatedAt(paymentId, LocalDateTime.now().minusMinutes(9));
    when(refundRepository.findStalePending(any(), anyInt())).thenReturn(List.of(overdue));
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(tossPaymentClient.queryRefundStatus("toss-payment-key", null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.NOT_FOUND, null));

    scanner.scan();

    verify(refundFinalizer, never()).complete(any(), any(), any());
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  @Test
  void PENDING_환불_재조회가_실패하면_강제_종결하지_않고_PENDING을_유지한다() {
    UUID paymentId = UUID.randomUUID();
    Payment payment = Payment.builder().id(paymentId).pgPaymentKey("toss-payment-key").build();
    Refund overdue = refundPendingCreatedAt(paymentId, LocalDateTime.now().minusMinutes(9));
    when(refundRepository.findStalePending(any(), anyInt())).thenReturn(List.of(overdue));
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(tossPaymentClient.queryRefundStatus("toss-payment-key", null, 3_000L))
        .thenThrow(new RuntimeException("timeout"));

    scanner.scan();

    verify(refundFinalizer, never()).complete(any(), any(), any());
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  // 사이클당 상한 — 정체 건 전량이 아니라 상한만큼만 조회해야 한 사이클이 유한 시간에 끝난다.
  @Test
  void 정체_건은_사이클당_상한만큼만_조회한다() {
    ReflectionTestUtils.setField(scanner, "maxPerCycle", 3);

    scanner.scan();

    verify(paymentRepository).findStalePending(any(), eq(3));
    verify(walletChargeRepository).findStalePending(any(), eq(3));
    verify(refundRepository).findStalePending(any(), eq(3));
  }

  // 시간 예산 — 예산이 이미 소진된 사이클은 조회된 건에 손대지 않고 통째로 다음 주기에 넘긴다.
  @Test
  void 시간_예산을_넘기면_남은_건을_처리하지_않고_다음_주기로_미룬다() {
    ReflectionTestUtils.setField(scanner, "cycleBudgetSeconds", 0L);
    Payment overdue = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any(), anyInt())).thenReturn(List.of(overdue));

    scanner.scan();

    verify(tossPaymentClient, never()).queryPaymentStatus(any());
    verify(finalizer, never()).finalizePending(any(), any(), any(), any());
  }

  // 예산 소진 시점까지 처리한 건은 확정하고, 그 뒤 건만 이월한다(사이클 중간에 끊겨도 진행은 남는다).
  @Test
  void 시간_예산_소진_전까지_처리한_건은_확정하고_나머지만_이월한다() {
    ReflectionTestUtils.setField(scanner, "cycleBudgetSeconds", 1L);
    Payment first = paymentWithKeyCreatedAt("toss-key-1", LocalDateTime.now().minusMinutes(9));
    Payment second = paymentWithKeyCreatedAt("toss-key-2", LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any(), anyInt())).thenReturn(List.of(first, second));
    // 첫 건의 PG 조회가 예산(1초)을 다 써버린 상황 — 두 번째 건은 다음 주기로 넘어가야 한다.
    when(tossPaymentClient.queryPaymentStatus("toss-key-1"))
        .thenAnswer(
            invocation -> {
              Thread.sleep(1_100);
              return TossQueryResult.of(TossQueryResult.Status.APPROVED, "toss-tx-1");
            });

    scanner.scan();

    verify(finalizer).finalizePending(first.getId(), Payment.Status.APPROVED, "toss-tx-1", null);
    verify(tossPaymentClient, never()).queryPaymentStatus("toss-key-2");
    verify(finalizer, times(1)).finalizePending(any(), any(), any(), any());
  }
}
