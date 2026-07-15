package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.dto.PgConfirmCommand;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.application.usecase.PaymentUseCase;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PaymentEvent;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService implements PaymentUseCase {

  private static final String COMPLETED_TOPIC = "payment.completed.events";
  private static final String SETTLEMENT_SOURCE_TOPIC = "payment.settlement.events";
  private static final String SETTLEMENT_EVENT_TYPE = "PaymentSettlementCompleted";

  private final PaymentRepository paymentRepository;
  private final WalletRepository walletRepository;
  private final WalletTransactionRepository walletTransactionRepository;
  private final PaymentEventRepository paymentEventRepository;
  private final OrderValidationClient orderValidationClient;
  private final TossPaymentClient tossPaymentClient;
  private final DomainEventPublisher eventPublisher;
  private final PaymentFinalizer paymentFinalizer;

  public PaymentService(
      PaymentRepository paymentRepository,
      WalletRepository walletRepository,
      WalletTransactionRepository walletTransactionRepository,
      PaymentEventRepository paymentEventRepository,
      OrderValidationClient orderValidationClient,
      TossPaymentClient tossPaymentClient,
      DomainEventPublisher eventPublisher,
      PaymentFinalizer paymentFinalizer) {
    this.paymentRepository = paymentRepository;
    this.walletRepository = walletRepository;
    this.walletTransactionRepository = walletTransactionRepository;
    this.paymentEventRepository = paymentEventRepository;
    this.orderValidationClient = orderValidationClient;
    this.tossPaymentClient = tossPaymentClient;
    this.eventPublisher = eventPublisher;
    this.paymentFinalizer = paymentFinalizer;
  }

  @Override
  @Transactional
  public PaymentResult payWithWallet(PayWithWalletCommand command) {
    String requestHash =
        RequestHasher.hash(
            command.orderId().toString(), command.memberId().toString(),
            command.amount().toString(), Payment.Method.WALLET.name());

    Optional<Payment> existing = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), requestHash);
    }

    // 결제 요청 접수 시 브라우저 제출값을 그대로 신뢰하지 않고 Order의 진짜 값과 대조(#17, B5 — 현재는 스텁).
    OrderValidationResult orderResult =
        orderValidationClient.validate(command.orderId(), command.memberId(), command.amount());
    if (!orderResult.valid()
        || !Objects.equals(orderResult.memberId(), command.memberId())
        || !Objects.equals(orderResult.amount(), command.amount())) {
      throw new BusinessException(PaymentErrorCode.ORDER_VALIDATION_FAILED);
    }

    Wallet wallet = walletRepository.findOrCreateByMemberId(command.memberId());

    // 잔액 차감(#8) — SELECT-then-UPDATE 없이 단일 조건부 UPDATE로 원자처리.
    int affected = walletRepository.tryDeduct(wallet.getId(), command.amount());
    if (affected == 0) {
      throw new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE);
    }
    // D3 — UPDATE 성공 후 같은 TX 재조회(row lock이 커밋까지 유지되므로 재조회 값이 정답, §4.2).
    long balanceAfter =
        walletRepository
            .findByMemberId(command.memberId())
            .map(Wallet::getBalance)
            .orElse(wallet.getBalance() - command.amount());

    LocalDateTime now = LocalDateTime.now();

    walletTransactionRepository.save(
        WalletTransaction.deductOf(
            wallet.getId(), command.amount(), balanceAfter, command.idempotencyKey()));

    Payment saved =
        paymentRepository.save(
            Payment.approvedWallet(
                command.orderId(),
                command.memberId(),
                command.amount(),
                command.idempotencyKey(),
                requestHash));

    paymentEventRepository.save(
        PaymentEvent.builder()
            .paymentId(saved.getId())
            .type(PaymentEvent.Type.APPROVE)
            .amount(command.amount())
            .createdAt(now)
            .build());

    // (Day2 남겨둔 항목, 2026-06-21 반영) WALLET 즉시승인도 PG confirm/웹훅 경로와 동일하게 payment.completed.events 발행.
    eventPublisher.publish(
        "PAYMENT",
        saved.getId(),
        COMPLETED_TOPIC,
        new PaymentCompletedPayload(
            saved.getId(),
            saved.getOrderId(),
            saved.getMemberId(),
            saved.getAmount(),
            saved.getMethod().name(),
            null,
            saved.getApprovedAt()));

    return PaymentResult.of(saved.getId(), saved.getStatus().name(), saved.getAmount());
  }

  // 7-13 plan WS-C — confirm 단일 진입점(get-or-create). TX는 아래 세 단위로 분리(D5):
  // [TX1] tryReserveForConfirm(어댑터 내부 saveAndFlush) -> [TX 밖] 토스 confirm -> [TX2] PaymentFinalizer.
  // 메서드 자체엔 @Transactional을 걸지 않는다 — 걸면 토스 HTTP 호출이 DB 커넥션을 점유한 채 대기하게 된다.
  @Override
  public PaymentResult confirmPg(PgConfirmCommand command) {
    String keyHash = RequestHasher.hash(command.paymentKey());

    // [1] Order 검증(TX 밖, 하자드17·22 유지) — 예약 INSERT보다 먼저 둬서 검증 실패 주문이 order_id를
    // 영구 선점하는 걸 막는다. 트레이드오프: 레이스 시 두 요청 모두 검증 호출(읽기전용·짧은 타임아웃이라 허용).
    OrderValidationResult orderResult =
        orderValidationClient.validate(command.orderId(), command.memberId(), command.amount());
    if (!orderResult.valid()
        || !Objects.equals(orderResult.memberId(), command.memberId())
        || !Objects.equals(orderResult.amount(), command.amount())) {
      throw new BusinessException(PaymentErrorCode.ORDER_VALIDATION_FAILED);
    }

    // [2] 예약 INSERT(TX1) — order_id 유니크 충돌 시 PG 호출 없이 4갈래 분기로 넘어간다.
    Optional<Payment> reserved =
        paymentRepository.tryReserveForConfirm(
            Payment.reserveForConfirm(
                command.orderId(), command.memberId(), command.amount(), command.paymentKey(), keyHash));

    if (reserved.isEmpty()) {
      return resolveConflict(command.orderId(), keyHash);
    }

    // [3] 토스 confirm(TX 밖, DB 커넥션 비점유) — D4: 아웃바운드 멱등 헤더는 paymentKey.
    TossConfirmResult confirmResult =
        tossPaymentClient.confirmPayment(
            command.paymentKey(), command.orderId(), command.amount(), command.paymentKey());

    Payment.Status newStatus =
        confirmResult.approved() ? Payment.Status.APPROVED : Payment.Status.FAILED;

    // [4] 확정(TX2) — PaymentFinalizer 재사용. lost-race면 이 스레드의 계산값이 아니라 현재 DB상태를 반환.
    UUID paymentId = reserved.get().getId();
    return paymentFinalizer
        .finalizePending(paymentId, newStatus, confirmResult.pgTxId(), confirmResult.reason())
        .map(p -> PaymentResult.of(p.getId(), p.getStatus().name(), p.getAmount()))
        .orElseGet(() -> currentStateOf(paymentId));
  }

  // order_id 예약 충돌 — 기존 행 상태·pgPaymentKeyHash로 4갈래 분기(D6).
  private PaymentResult resolveConflict(UUID orderId, String keyHash) {
    Payment existing =
        paymentRepository
            .findByOrderId(orderId)
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND)); // 이론상 도달 불가
    boolean keyMatches = keyHash.equals(existing.getPgPaymentKeyHash());
    boolean terminal = existing.getStatus() != Payment.Status.PAYMENT_PENDING;

    if (keyMatches) {
      // 레이스(비종결) 또는 정상 멱등(종결) — 둘 다 저장된 상태 그대로 반환(200 + PAYMENT_PENDING 가능, D6).
      return PaymentResult.of(existing.getId(), existing.getStatus().name(), existing.getAmount());
    }
    throw new BusinessException(
        terminal
            ? PaymentErrorCode.ALREADY_PROCESSED // 종결 + key 불일치
            : PaymentErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS); // PENDING + key 불일치(신규 하자드)
  }

  private PaymentResult currentStateOf(UUID paymentId) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalStateException("확정 직후 Payment 소실: " + paymentId));
    return PaymentResult.of(payment.getId(), payment.getStatus().name(), payment.getAmount());
  }

  @Override
  public PaymentResult getPayment(UUID paymentId) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    return PaymentResult.of(payment.getId(), payment.getStatus().name(), payment.getAmount());
  }

  private PaymentResult replayOrConflict(Payment existing, String requestHash) {
    if (!Objects.equals(existing.getRequestHash(), requestHash)) {
      throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
    return new PaymentResult(
        existing.getId(),
        existing.getStatus().name(),
        existing.getAmount(),
        existing.getPgPaymentKey());
  }

  @Override
  @Transactional
  public void backfillSellerAndProduct(UUID orderId, UUID sellerId, UUID productId) {
    int affected = paymentRepository.tryFillSellerAndProduct(orderId, sellerId, productId);
    if (affected == 0) {
      // 이미 채워져 있거나 대상 Payment(APPROVED)가 없음 — 멱등하게 무시.
      return;
    }

    Payment filled =
        paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED).orElse(null);
    if (filled == null) {
      return;
    }

    // 사후채움 직후에만 발행(B2) — 그 전에 발행하면 sellerId/productId가 비어 하자드 #16과 동일한 문제가 생김.
    eventPublisher.publish(
        "PAYMENT",
        filled.getId(),
        SETTLEMENT_SOURCE_TOPIC,
        new SettlementSourcePayload(
            UUID.randomUUID().toString(),
            SETTLEMENT_EVENT_TYPE,
            LocalDateTime.now(),
            filled.getId(),
            filled.getOrderId(),
            filled.getSellerId(),
            filled.getMemberId(),
            filled.getProductId(),
            filled.getAmount(),
            filled.getAmount(),
            filled.getApprovedAt()));
  }

  private record SettlementSourcePayload(
      String eventId,
      String eventType,
      LocalDateTime occurredAt,
      UUID paymentId,
      UUID orderId,
      UUID sellerId,
      UUID buyerId,
      UUID productId,
      Long orderAmount,
      Long paidAmount,
      LocalDateTime paidAt) {}
}
