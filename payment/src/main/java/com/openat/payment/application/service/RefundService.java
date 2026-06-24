package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossRefundResult;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundCompletedPayload;
import com.openat.payment.application.dto.RefundFailedPayload;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.dto.RefundSettlementSourcePayload;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.application.usecase.RefundUseCase;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 환불(§6) — 소유자 검증(Order 재호출 없음, Payment.memberId 내부 비교) + 환불가능액 조건부 UPDATE(#13)
// + PG 환불 호출 멱등키(#12). WALLET 결제는 PG 호출 없이 지갑으로 즉시 반환, PG 결제만 토스 결제취소 호출.
@Service
public class RefundService implements RefundUseCase {

    private static final String COMPLETED_TOPIC = "refund.completed.events";
    private static final String FAILED_TOPIC = "refund.failed.events";
    private static final String SETTLEMENT_SOURCE_TOPIC = "refund.settlement-source.events";

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final TossPaymentClient tossPaymentClient;
    private final OutboxEventWriter outboxEventWriter;

    public RefundService(RefundRepository refundRepository, PaymentRepository paymentRepository,
            WalletRepository walletRepository, WalletTransactionRepository walletTransactionRepository,
            TossPaymentClient tossPaymentClient, OutboxEventWriter outboxEventWriter) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.tossPaymentClient = tossPaymentClient;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    @Transactional
    public RefundResult requestRefund(RefundCommand command) {
        String requestHash = RequestHasher.hash(command.paymentId().toString(), command.amount().toString());

        Optional<Refund> existing = refundRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        Payment payment = paymentRepository.findById(command.paymentId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        // 소유자 검증 — Order 재호출 없음(이미 결제 시점에 검증된 memberId와 내부 비교만, api_event_specification.md 403).
        if (!Objects.equals(payment.getMemberId(), command.memberId())) {
            throw new BusinessException(PaymentErrorCode.FORBIDDEN);
        }

        // 환불가능액 원자 검증(#13) — SELECT-then-UPDATE 없이 단일 조건부 UPDATE로 원자처리.
        int affected = paymentRepository.tryIncreaseRefundedAmount(payment.getId(), command.amount());
        if (affected == 0) {
            throw new BusinessException(PaymentErrorCode.EXCEED_REFUNDABLE_AMOUNT);
        }

        LocalDateTime now = LocalDateTime.now();
        Refund pending = refundRepository.save(Refund.builder()
                .paymentId(payment.getId())
                .amount(command.amount())
                .status(Refund.Status.PENDING)
                .reason(command.reason())
                .idempotencyKey(command.idempotencyKey())
                .requestHash(requestHash)
                .createdAt(now)
                .build());

        if (payment.getMethod() == Payment.Method.WALLET) {
            // WALLET 결제는 PG 호출 없음 — 지갑으로 즉시 반환.
            creditWallet(payment, pending);
            return completeRefund(pending, payment, null);
        }

        // PG 결제 — 환불 호출에도 멱등키 부착(#12). 이 호출은 원래도 동기 응답 구조라 confirm 같은 별도 단계 없음.
        TossRefundResult pgResult = tossPaymentClient.refundPayment(
                payment.getPgPaymentKey(), command.amount(), command.idempotencyKey());

        return switch (pgResult.status()) {
            case COMPLETE -> completeRefund(pending, payment, pgResult.pgRefundKey());
            case FAILED -> failRefund(pending, payment, pgResult.reason());
            case UNKNOWN -> {
                // 타임아웃 등 응답 불확실 — 보정하지 않고 PENDING 유지, 보조 웹훅이 나중에 확정.
                yield new RefundResult(pending.getId(), pending.getPaymentId(), pending.getAmount(),
                        pending.getStatus().name());
            }
        };
    }

    private void creditWallet(Payment payment, Refund refund) {
        Wallet wallet = walletRepository.findByMemberId(payment.getMemberId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        walletRepository.charge(wallet.getId(), refund.getAmount());
        long balanceAfter = wallet.getBalance() + refund.getAmount();

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(WalletTransaction.Type.REFUND)
                .amount(refund.getAmount())
                .balanceAfter(balanceAfter)
                .idempotencyKey(refund.getIdempotencyKey())
                .createdAt(LocalDateTime.now())
                .build());
    }

    private RefundResult completeRefund(Refund pending, Payment payment, String pgRefundKey) {
        LocalDateTime completedAt = LocalDateTime.now();
        int affected = refundRepository.tryTransitionFromPending(
                pending.getId(), Refund.Status.COMPLETE, pgRefundKey, completedAt);
        if (affected == 0) {
            Refund current = refundRepository.findById(pending.getId()).orElse(pending);
            return new RefundResult(current.getId(), current.getPaymentId(), current.getAmount(),
                    current.getStatus().name());
        }

        outboxEventWriter.write("REFUND", pending.getId(), COMPLETED_TOPIC, new RefundCompletedPayload(
                pending.getId(), payment.getId(), payment.getOrderId(), pending.getAmount(), completedAt));

        // B6/A6 — sellerId가 사후채움 전이라 null이어도 그대로 발행(정산 쪽 보류/재시도 전제, plan.md B6).
        outboxEventWriter.write("REFUND", pending.getId(), SETTLEMENT_SOURCE_TOPIC, new RefundSettlementSourcePayload(
                pending.getId(), payment.getId(), payment.getOrderId(), payment.getSellerId(),
                payment.getMemberId(), pending.getAmount(), pending.getReason(), Refund.Status.COMPLETE.name(),
                completedAt));

        return new RefundResult(pending.getId(), pending.getPaymentId(), pending.getAmount(),
                Refund.Status.COMPLETE.name());
    }

    private RefundResult failRefund(Refund pending, Payment payment, String reason) {
        int affected = refundRepository.tryTransitionFromPending(pending.getId(), Refund.Status.FAILED, null, null);
        if (affected == 0) {
            Refund current = refundRepository.findById(pending.getId()).orElse(pending);
            return new RefundResult(current.getId(), current.getPaymentId(), current.getAmount(),
                    current.getStatus().name());
        }

        // PG가 명시적으로 거절했으므로 한도 보정(원복) — UNKNOWN(타임아웃)은 보정하지 않음(나중에 웹훅이 확정).
        paymentRepository.tryDecreaseRefundedAmount(payment.getId(), pending.getAmount());

        outboxEventWriter.write("REFUND", pending.getId(), FAILED_TOPIC,
                new RefundFailedPayload(pending.getId(), payment.getId(), payment.getOrderId(), reason));

        return new RefundResult(pending.getId(), pending.getPaymentId(), pending.getAmount(),
                Refund.Status.FAILED.name());
    }

    @Override
    public RefundResult getRefund(UUID refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return new RefundResult(refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getStatus().name());
    }

    @Override
    public RefundHistoryResult getRefundHistories(UUID memberId, int page, int size) {
        List<Refund> refunds = refundRepository.findByMemberId(memberId, page, size);
        long totalCount = refundRepository.countByMemberId(memberId);
        int totalPages = (int) Math.ceil(totalCount / (double) size);

        List<RefundResult> content = refunds.stream()
                .map(r -> new RefundResult(r.getId(), r.getPaymentId(), r.getAmount(), r.getStatus().name()))
                .toList();

        return new RefundHistoryResult(content, totalPages);
    }

    private RefundResult replayOrConflict(Refund existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return new RefundResult(existing.getId(), existing.getPaymentId(), existing.getAmount(),
                existing.getStatus().name());
    }
}
