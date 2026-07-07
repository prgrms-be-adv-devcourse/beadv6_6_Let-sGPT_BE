package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.PayWithPgCommand;
import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.dto.PaymentFailedPayload;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.dto.PgConfirmCommand;
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
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService implements PaymentUseCase {

    private static final String COMPLETED_TOPIC = "payment.completed.events";
    private static final String FAILED_TOPIC = "payment.failed.events";
    private static final String SETTLEMENT_SOURCE_TOPIC = "payment.settlement.events";
    private static final String SETTLEMENT_EVENT_TYPE = "PaymentSettlementCompleted";

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderValidationClient orderValidationClient;
    private final TossPaymentClient tossPaymentClient;
    private final OutboxEventWriter outboxEventWriter;

    public PaymentService(PaymentRepository paymentRepository, WalletRepository walletRepository,
            WalletTransactionRepository walletTransactionRepository, PaymentEventRepository paymentEventRepository,
            OrderValidationClient orderValidationClient, TossPaymentClient tossPaymentClient,
            OutboxEventWriter outboxEventWriter) {
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.orderValidationClient = orderValidationClient;
        this.tossPaymentClient = tossPaymentClient;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    @Transactional
    public PaymentResult payWithWallet(PayWithWalletCommand command) {
        String requestHash = RequestHasher.hash(
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

        Wallet wallet = getOrCreateWallet(command.memberId());

        // 잔액 차감(#8) — SELECT-then-UPDATE 없이 단일 조건부 UPDATE로 원자처리.
        int affected = walletRepository.tryDeduct(wallet.getId(), command.amount());
        if (affected == 0) {
            throw new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE);
        }
        long balanceAfter = wallet.getBalance() - command.amount();

        LocalDateTime now = LocalDateTime.now();

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(WalletTransaction.Type.DEDUCT)
                .amount(command.amount())
                .balanceAfter(balanceAfter)
                .idempotencyKey(command.idempotencyKey())
                .createdAt(now)
                .build());

        Payment saved = paymentRepository.save(Payment.builder()
                .orderId(command.orderId())
                .memberId(command.memberId())
                .amount(command.amount())
                .method(Payment.Method.WALLET)
                .status(Payment.Status.APPROVED)
                .idempotencyKey(command.idempotencyKey())
                .requestHash(requestHash)
                .approvedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        paymentEventRepository.save(PaymentEvent.builder()
                .paymentId(saved.getId())
                .type(PaymentEvent.Type.APPROVE)
                .amount(command.amount())
                .createdAt(now)
                .build());

        // (Day2 남겨둔 항목, 2026-06-21 반영) WALLET 즉시승인도 PG confirm/웹훅 경로와 동일하게 payment.completed.events 발행.
        outboxEventWriter.write("PAYMENT", saved.getId(), COMPLETED_TOPIC, new PaymentCompletedPayload(
                saved.getId(), saved.getOrderId(), saved.getMemberId(), saved.getAmount(),
                saved.getMethod().name(), null, saved.getApprovedAt()));

        return PaymentResult.of(saved.getId(), saved.getStatus().name(), saved.getAmount());
    }

    @Override
    @Transactional
    public PaymentResult payWithPg(PayWithPgCommand command) {
        String requestHash = RequestHasher.hash(
                command.orderId().toString(), command.memberId().toString(),
                command.amount().toString(), Payment.Method.PG.name());

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        OrderValidationResult orderResult =
                orderValidationClient.validate(command.orderId(), command.memberId(), command.amount());
        if (!orderResult.valid()
                || !Objects.equals(orderResult.memberId(), command.memberId())
                || !Objects.equals(orderResult.amount(), command.amount())) {
            throw new BusinessException(PaymentErrorCode.ORDER_VALIDATION_FAILED);
        }

        LocalDateTime now = LocalDateTime.now();

        // A16(2026-06-21) — 서버는 PG에 아무것도 요청하지 않음. 브라우저의 토스 SDK가 PG를 직접 호출하므로
        // PENDING row만 만들고 끝(pgPaymentKey는 confirmPg에서 confirm 요청으로 전달받아 채움).
        Payment pending = paymentRepository.save(Payment.builder()
                .orderId(command.orderId())
                .memberId(command.memberId())
                .amount(command.amount())
                .method(Payment.Method.PG)
                .pgProvider("TOSS")
                .status(Payment.Status.PAYMENT_PENDING)
                .idempotencyKey(command.idempotencyKey())
                .requestHash(requestHash)
                .createdAt(now)
                .updatedAt(now)
                .build());

        return PaymentResult.of(pending.getId(), pending.getStatus().name(), pending.getAmount());
    }

    @Override
    @Transactional
    public PaymentResult confirmPg(PgConfirmCommand command) {
        // 하자드20 — 같은 Idempotency-Key를 POST /payments와 재사용하지만, 여기서는 #7(바디해시 대조)을 쓰지 않고
        // Payment.status 기준으로만 멱등성을 판단한다(아래 "이미 종결됐으면 그대로 반환").
        Payment payment = paymentRepository.findByOrderIdAndStatus(command.orderId(), Payment.Status.PAYMENT_PENDING)
                .or(() -> paymentRepository.findByOrderIdAndStatus(command.orderId(), Payment.Status.APPROVED))
                .or(() -> paymentRepository.findByOrderIdAndStatus(command.orderId(), Payment.Status.FAILED))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        if (payment.getStatus() != Payment.Status.PAYMENT_PENDING) {
            // 이미 다른 confirm 호출(재시도)이나 보조 웹훅/TTL스캐너가 먼저 확정함 — 멱등 반환, PG 재호출 없음.
            return PaymentResult.of(payment.getId(), payment.getStatus().name(), payment.getAmount());
        }

        // 하자드17·22 — ②(POST /payments) 시점과 별개로, confirm 시점에도 Order 재검증(가용성 트레이드오프 인지).
        OrderValidationResult orderResult =
                orderValidationClient.validate(command.orderId(), command.memberId(), command.amount());
        if (!orderResult.valid()
                || !Objects.equals(orderResult.memberId(), command.memberId())
                || !Objects.equals(orderResult.amount(), command.amount())
                || !Objects.equals(payment.getAmount(), command.amount())) {
            throw new BusinessException(PaymentErrorCode.ORDER_VALIDATION_FAILED);
        }

        // 신-하자드9 — PG를 호출하기 *전에* 먼저 pgPaymentKey를 기록(PG는 승인했는데 우리 기록만 끊기는 경우,
        // TTL스캐너가 이 키로 PG에 조회해서 회복 가능하게 함).
        paymentRepository.updatePgPaymentKey(payment.getId(), command.paymentKey());

        // A10(대상 정정) — 멱등키 부착 대상이 confirm 호출로 이동.
        TossConfirmResult confirmResult = tossPaymentClient.confirmPayment(
                command.paymentKey(), command.orderId(), command.amount(), command.idempotencyKey());

        Payment.Status newStatus = confirmResult.approved() ? Payment.Status.APPROVED : Payment.Status.FAILED;
        LocalDateTime approvedAt = confirmResult.approved() ? LocalDateTime.now() : null;

        // 하자드10 — 보조 웹훅·TTL스캐너와 동시에 같은 row를 만질 수 있어 조건부 UPDATE로 원자처리.
        int affected = paymentRepository.tryTransitionFromPending(
                payment.getId(), newStatus, confirmResult.pgTxId(), approvedAt);
        if (affected == 0) {
            Payment current = paymentRepository.findById(payment.getId()).orElse(payment);
            return PaymentResult.of(current.getId(), current.getStatus().name(), current.getAmount());
        }

        Payment updated = paymentRepository.findById(payment.getId()).orElse(payment);
        if (newStatus == Payment.Status.APPROVED) {
            outboxEventWriter.write("PAYMENT", updated.getId(), COMPLETED_TOPIC, new PaymentCompletedPayload(
                    updated.getId(), updated.getOrderId(), updated.getMemberId(), updated.getAmount(),
                    updated.getMethod().name(), updated.getPgTxId(), updated.getApprovedAt()));
        } else {
            // 하자드23 — 카드거절 등 일반 거절(PG_REJECTED)과 토스측 자체 EXPIRED를 reason으로 구분.
            outboxEventWriter.write("PAYMENT", updated.getId(), FAILED_TOPIC,
                    new PaymentFailedPayload(updated.getId(), updated.getOrderId(), confirmResult.reason()));
        }

        return PaymentResult.of(updated.getId(), updated.getStatus().name(), updated.getAmount());
    }

    @Override
    public PaymentResult getPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return PaymentResult.of(payment.getId(), payment.getStatus().name(), payment.getAmount());
    }

    private PaymentResult replayOrConflict(Payment existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return new PaymentResult(existing.getId(), existing.getStatus().name(), existing.getAmount(),
                existing.getPgPaymentKey());
    }

    private Wallet getOrCreateWallet(UUID memberId) {
        return walletRepository.findByMemberId(memberId)
                .orElseGet(() -> walletRepository.save(Wallet.builder()
                        .memberId(memberId)
                        .balance(0L)
                        .createdAt(LocalDateTime.now())
                        .build()));
    }

    @Override
    @Transactional
    public void backfillSellerAndProduct(UUID orderId, UUID sellerId, UUID productId) {
        int affected = paymentRepository.tryFillSellerAndProduct(orderId, sellerId, productId);
        if (affected == 0) {
            // 이미 채워져 있거나 대상 Payment(APPROVED)가 없음 — 멱등하게 무시.
            return;
        }

        Payment filled = paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED).orElse(null);
        if (filled == null) {
            return;
        }

        // 사후채움 직후에만 발행(B2) — 그 전에 발행하면 sellerId/productId가 비어 하자드 #16과 동일한 문제가 생김.
        outboxEventWriter.write("PAYMENT", filled.getId(), SETTLEMENT_SOURCE_TOPIC, new SettlementSourcePayload(
                UUID.randomUUID().toString(), SETTLEMENT_EVENT_TYPE, LocalDateTime.now(),
                filled.getId(), filled.getOrderId(), filled.getSellerId(), filled.getMemberId(),
                filled.getProductId(), filled.getAmount(), filled.getAmount(), filled.getApprovedAt()));
    }

    private record SettlementSourcePayload(String eventId, String eventType, LocalDateTime occurredAt,
            UUID paymentId, UUID orderId, UUID sellerId, UUID buyerId,
            UUID productId, Long orderAmount, Long paidAmount, LocalDateTime paidAt) {
    }
}
