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
import com.openat.payment.domain.model.WalletCharge;
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

// TTL 스캐너 단발화(D9, Q101 확정, 7-13 plan §6.5 WS-H) — 조기 폴링 제거 후 마지노선 하나로만 확정하는지,
// 그리고 도메인별 예산 분리·종결불가 행 백오프(커서 페이지네이션)로 굶김/livelock을 막는지 검증.
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
    when(walletChargeRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of());
    when(refundRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of());
    when(paymentRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of());
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

  private WalletCharge chargeWithKeyCreatedAt(String pgPaymentKey, LocalDateTime createdAt) {
    return WalletCharge.builder()
        .id(UUID.randomUUID())
        .pgPaymentKey(pgPaymentKey)
        .status(WalletCharge.Status.PENDING)
        .createdAt(createdAt)
        .build();
  }

  @Test
  void 마지노선_전에는_조회하지_않는다() {
    Payment youngPending = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(5));
    when(paymentRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(youngPending));

    scanner.scan();

    verify(tossPaymentClient, never()).queryPaymentStatus(any());
    verify(finalizer, never()).finalizePending(any(), any(), any(), any());
  }

  @Test
  void 마지노선_조회가_에러면_즉시_FORCED_TIMEOUT으로_종결한다() {
    Payment overdue = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(overdue));
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
    when(refundRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(overdue));
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
    when(refundRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(young));

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
    when(refundRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(overdue));
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
    when(refundRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(overdue));
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

    verify(paymentRepository).findStalePending(any(), any(), eq(3));
    verify(walletChargeRepository).findStalePending(any(), any(), eq(3));
    verify(refundRepository).findStalePending(any(), any(), eq(3));
  }

  // 시간 예산 — 예산이 이미 소진된 사이클은 조회된 건에 손대지 않고 통째로 다음 주기에 넘긴다.
  @Test
  void 시간_예산을_넘기면_남은_건을_처리하지_않고_다음_주기로_미룬다() {
    ReflectionTestUtils.setField(scanner, "cycleBudgetSeconds", 0L);
    Payment overdue = paymentWithKeyCreatedAt(LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(overdue));

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
    when(paymentRepository.findStalePending(any(), any(), anyInt()))
        .thenReturn(List.of(first, second));
    // 첫 건의 PG 조회가 예산(도메인당 1/3초)을 다 써버린 상황 — 두 번째 건은 다음 주기로 넘어가야 한다.
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

  // 지적1 — 결제가 자기 예산을 소진해도 충전·환불은 자기 몫 예산으로 처리된다(공유 예산이면 여기서 굶었을 것).
  @Test
  void 결제가_자기_예산을_소진해도_충전과_환불은_자기_예산으로_처리된다() {
    // 전체 예산 1초 → 도메인당 1/3초. 결제 한 건이 1.2초를 써 전체 예산을 넘기지만,
    // 충전·환불은 각자 시작 시점부터 자기 예산을 새로 받으므로 처리돼야 한다.
    ReflectionTestUtils.setField(scanner, "cycleBudgetSeconds", 1L);

    Payment slowPayment =
        paymentWithKeyCreatedAt("slow-payment-key", LocalDateTime.now().minusMinutes(9));
    when(paymentRepository.findStalePending(any(), any(), anyInt()))
        .thenReturn(List.of(slowPayment));
    when(tossPaymentClient.queryPaymentStatus("slow-payment-key"))
        .thenAnswer(
            invocation -> {
              Thread.sleep(1_200);
              return TossQueryResult.of(TossQueryResult.Status.APPROVED, "tx-slow");
            });

    WalletCharge charge = chargeWithKeyCreatedAt("charge-key", LocalDateTime.now().minusMinutes(9));
    when(walletChargeRepository.findStalePending(any(), any(), anyInt()))
        .thenReturn(List.of(charge));
    when(tossPaymentClient.queryPaymentStatus("charge-key"))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "tx-charge"));

    UUID refundPaymentId = UUID.randomUUID();
    Payment refundPayment =
        Payment.builder().id(refundPaymentId).pgPaymentKey("refund-payment-key").build();
    Refund refund = refundPendingCreatedAt(refundPaymentId, LocalDateTime.now().minusMinutes(9));
    when(refundRepository.findStalePending(any(), any(), anyInt())).thenReturn(List.of(refund));
    when(paymentRepository.findById(refundPaymentId)).thenReturn(Optional.of(refundPayment));
    when(tossPaymentClient.queryRefundStatus("refund-payment-key", null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "refund-tx"));

    scanner.scan();

    verify(chargeFinalizer)
        .finalizePending(charge.getId(), WalletCharge.Status.APPROVED, "tx-charge");
    verify(refundFinalizer).complete(refund.getId(), refundPayment, "refund-tx");
  }

  // 지적2 — 상한을 넘는 영구 PENDING(poison) 환불이 선두를 점유해도, 커서+백오프로 다음 주기엔 뒤의
  // 확정 가능 환불에 도달해 처리한다.
  @Test
  void 선두를_점유한_poison_환불_뒤의_확정_가능_환불이_커서와_백오프로_처리된다() {
    ReflectionTestUtils.setField(scanner, "maxPerCycle", 2);

    UUID poisonPaymentId = UUID.randomUUID();
    Payment poisonPayment =
        Payment.builder().id(poisonPaymentId).pgPaymentKey("poison-pk").build();
    UUID goodPaymentId = UUID.randomUUID();
    Payment goodPayment = Payment.builder().id(goodPaymentId).pgPaymentKey("good-pk").build();

    // 오래된 순: poison1(-11), poison2(-10), good(-9). 상한(2)이라 1주기엔 poison 둘만 시도하고 good엔 못 닿는다.
    Refund poison1 = refundPendingCreatedAt(poisonPaymentId, LocalDateTime.now().minusMinutes(11));
    Refund poison2 = refundPendingCreatedAt(poisonPaymentId, LocalDateTime.now().minusMinutes(10));
    Refund good = refundPendingCreatedAt(goodPaymentId, LocalDateTime.now().minusMinutes(9));
    List<Refund> all = List.of(poison1, poison2, good); // createdAt 오름차순

    // 커서(2번째 인자) 이후만, limit(3번째)만큼 반환 — 실제 키셋 페이지네이션을 흉내.
    when(refundRepository.findStalePending(any(), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              LocalDateTime cursor = invocation.getArgument(1);
              int limit = invocation.getArgument(2);
              return all.stream()
                  .filter(r -> cursor == null || r.getCreatedAt().isAfter(cursor))
                  .limit(limit)
                  .toList();
            });

    when(paymentRepository.findById(poisonPaymentId)).thenReturn(Optional.of(poisonPayment));
    when(paymentRepository.findById(goodPaymentId)).thenReturn(Optional.of(goodPayment));
    // poison은 항상 NOT_FOUND(종결 불가), good은 APPROVED.
    when(tossPaymentClient.queryRefundStatus("poison-pk", null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.NOT_FOUND, null));
    when(tossPaymentClient.queryRefundStatus("good-pk", null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "good-refund-tx"));
    when(refundFinalizer.complete(good.getId(), goodPayment, "good-refund-tx"))
        .thenReturn(Optional.of(good));

    // 1주기: 상한(2)에 걸려 poison 둘만 시도하고 good엔 도달하지 못한다.
    scanner.scan();
    verify(refundFinalizer, never()).complete(eq(good.getId()), any(), any());

    // 2주기: poison 둘은 백오프로 건너뛰고 커서가 그 뒤로 전진해 good에 도달·확정한다.
    scanner.scan();
    verify(refundFinalizer).complete(good.getId(), goodPayment, "good-refund-tx");
  }
}
