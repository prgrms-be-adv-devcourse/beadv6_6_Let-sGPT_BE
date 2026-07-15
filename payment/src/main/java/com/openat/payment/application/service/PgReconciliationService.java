package com.openat.payment.application.service;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossPaymentDetail;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.dto.PgReconciliationSummary;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PgReconStatus;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.model.support.UuidV7Generator;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.infrastructure.reconciliation.ReconciliationDiscrepancyJpaEntity;
import com.openat.payment.infrastructure.reconciliation.ReconciliationDiscrepancyJpaEntity.DiscrepancyType;
import com.openat.payment.infrastructure.reconciliation.ReconciliationDiscrepancyJpaEntity.EntityType;
import com.openat.payment.infrastructure.reconciliation.ReconciliationDiscrepancyJpaRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// PG 대사(WS-0) — payment DB(payments/refunds) ↔ 토스 실제 거래를 대조해 pg_recon_status를 확정한다.
// 정산 대사(DailyPaymentSettlementService)는 이 서비스가 MATCHED로 확정한 행만 정산에 노출한다 — 반드시 정산 대사보다
// 먼저 실행돼야 한다(스케줄 순서: PG 대사 01:00 → 정산 대사 02:00, PgReconciliationScheduler/reconciliation.md 참고).
// reconcile()에 @Transactional 필요 — @Modifying 커스텀 쿼리(markPgReconResult 등)는 활성 트랜잭션이 없으면
// InvalidDataAccessApiUsageException("No active transaction for update or delete query")로 즉시 실패한다
// (SimpleJpaRepository의 save/delete와 달리 자동으로 트랜잭션을 열어주지 않음 — 실기동 검증으로 확인된 결함).
// 토스 HTTP 호출이 같은 트랜잭션 안에서 일어나 커넥션을 다소 오래 물지만, 야간 배치 볼륨 규모에서는 감수.
@Slf4j
@Service
public class PgReconciliationService {

    // 롤링 윈도우 — 오늘 배치 대상일 + 과거 미해소(MISMATCH/미조회) row 재시도(WS-0.5 hold 재유입).
    private static final int LOOKBACK_DAYS = 7;

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final TossPaymentClient tossPaymentClient;
    private final ReconciliationDiscrepancyJpaRepository discrepancyRepository;

    public PgReconciliationService(PaymentRepository paymentRepository, RefundRepository refundRepository,
            TossPaymentClient tossPaymentClient, ReconciliationDiscrepancyJpaRepository discrepancyRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.tossPaymentClient = tossPaymentClient;
        this.discrepancyRepository = discrepancyRepository;
    }

    @Transactional
    public PgReconciliationSummary reconcile(LocalDate businessDate) {
        LocalDateTime from = businessDate.minusDays(LOOKBACK_DAYS).atStartOfDay();
        LocalDateTime to = businessDate.plusDays(1).atStartOfDay();

        int[] paymentCounts = reconcilePayments(from, to);
        int[] refundCounts = reconcileRefunds(from, to);

        PgReconciliationSummary summary = new PgReconciliationSummary(businessDate.toString(),
                paymentCounts[0], paymentCounts[1], paymentCounts[2],
                refundCounts[0], refundCounts[1], refundCounts[2]);
        log.info("[PgReconciliationService] 완료: {}", summary);
        return summary;
    }

    // 반환: [matched, mismatched, skipped]
    private int[] reconcilePayments(LocalDateTime from, LocalDateTime to) {
        List<Payment> targets = paymentRepository.findForPgReconciliation(from, to);
        int matched = 0;
        int mismatched = 0;
        int skipped = 0;
        for (Payment payment : targets) {
            Outcome outcome = reconcilePayment(payment);
            switch (outcome) {
                case MATCHED -> matched++;
                case MISMATCH -> mismatched++;
                case SKIPPED -> skipped++;
            }
        }
        return new int[] {matched, mismatched, skipped};
    }

    private Outcome reconcilePayment(Payment payment) {
        if (payment.getPgPaymentKey() == null) {
            // PG 결제인데 승인 키가 없는 비정상 상태(WALLET은 애초에 대사 대상에서 제외됨, WS-0.3) — 그 자체가 불일치.
            recordMismatch(EntityType.PAYMENT, payment.getId(), payment.getApprovedAt(),
                    DiscrepancyType.NOT_FOUND_IN_PG, "pgPaymentKey 없음");
            paymentRepository.markPgReconResult(payment.getId(), PgReconStatus.MISMATCH, LocalDateTime.now());
            return Outcome.MISMATCH;
        }

        TossPaymentDetail detail;
        try {
            detail = tossPaymentClient.queryPaymentDetail(payment.getPgPaymentKey());
        } catch (Exception e) {
            log.warn("[PgReconciliationService] 토스 조회 실패, 다음 배치에 재시도: paymentId={}", payment.getId(), e);
            return Outcome.SKIPPED;
        }

        return switch (detail.status()) {
            case NOT_FOUND -> {
                recordMismatch(EntityType.PAYMENT, payment.getId(), payment.getApprovedAt(),
                        DiscrepancyType.NOT_FOUND_IN_PG, "토스에 해당 거래 없음");
                paymentRepository.markPgReconResult(payment.getId(), PgReconStatus.MISMATCH, LocalDateTime.now());
                yield Outcome.MISMATCH;
            }
            case FAILED -> {
                recordMismatch(EntityType.PAYMENT, payment.getId(), payment.getApprovedAt(),
                        DiscrepancyType.STATUS_MISMATCH,
                        "payment DB=APPROVED, 토스=FAILED");
                paymentRepository.markPgReconResult(payment.getId(), PgReconStatus.MISMATCH, LocalDateTime.now());
                yield Outcome.MISMATCH;
            }
            case APPROVED -> {
                if (detail.totalAmount() != null && !detail.totalAmount().equals(payment.getAmount())) {
                    recordMismatch(EntityType.PAYMENT, payment.getId(), payment.getApprovedAt(),
                            DiscrepancyType.AMOUNT_MISMATCH,
                            "payment DB=" + payment.getAmount() + ", 토스=" + detail.totalAmount());
                    paymentRepository.markPgReconResult(payment.getId(), PgReconStatus.MISMATCH, LocalDateTime.now());
                    yield Outcome.MISMATCH;
                }
                paymentRepository.markPgReconResult(payment.getId(), PgReconStatus.MATCHED, LocalDateTime.now());
                yield Outcome.MATCHED;
            }
        };
    }

    // 반환: [matched, mismatched, skipped]
    private int[] reconcileRefunds(LocalDateTime from, LocalDateTime to) {
        List<Refund> targets = refundRepository.findForPgReconciliation(from, to);
        if (targets.isEmpty()) {
            return new int[] {0, 0, 0};
        }

        List<UUID> paymentIds = targets.stream().map(Refund::getPaymentId).distinct().toList();
        Map<UUID, Payment> paymentsById = paymentRepository.findAllByIds(paymentIds).stream()
                .collect(Collectors.toMap(Payment::getId, Function.identity()));

        int matched = 0;
        int mismatched = 0;
        int skipped = 0;
        for (Refund refund : targets) {
            Outcome outcome = reconcileRefund(refund, paymentsById.get(refund.getPaymentId()));
            switch (outcome) {
                case MATCHED -> matched++;
                case MISMATCH -> mismatched++;
                case SKIPPED -> skipped++;
            }
        }
        return new int[] {matched, mismatched, skipped};
    }

    private Outcome reconcileRefund(Refund refund, Payment payment) {
        if (payment == null || payment.getPgPaymentKey() == null) {
            recordMismatch(EntityType.REFUND, refund.getId(), refund.getCompletedAt(),
                    DiscrepancyType.NOT_FOUND_IN_PG, "연결된 PG 결제 정보 없음");
            refundRepository.markPgReconResult(refund.getId(), PgReconStatus.MISMATCH, LocalDateTime.now());
            return Outcome.MISMATCH;
        }

        TossQueryResult result;
        try {
            result = tossPaymentClient.queryRefundStatus(payment.getPgPaymentKey(), refund.getPgRefundKey(), refund.getAmount());
        } catch (Exception e) {
            log.warn("[PgReconciliationService] 토스 환불조회 실패, 다음 배치에 재시도: refundId={}", refund.getId(), e);
            return Outcome.SKIPPED;
        }

        return switch (result.status()) {
            case APPROVED -> {
                refundRepository.markPgReconResult(refund.getId(), PgReconStatus.MATCHED, LocalDateTime.now());
                yield Outcome.MATCHED;
            }
            case FAILED, NOT_FOUND -> {
                recordMismatch(EntityType.REFUND, refund.getId(), refund.getCompletedAt(),
                        DiscrepancyType.STATUS_MISMATCH, "refund DB=COMPLETE, 토스=" + result.status());
                refundRepository.markPgReconResult(refund.getId(), PgReconStatus.MISMATCH, LocalDateTime.now());
                yield Outcome.MISMATCH;
            }
        };
    }

    private void recordMismatch(EntityType entityType, UUID entityId, LocalDateTime occurredAt,
            DiscrepancyType discrepancyType, String detail) {
        LocalDate businessDate = occurredAt != null ? occurredAt.toLocalDate() : LocalDate.now();
        discrepancyRepository.save(new ReconciliationDiscrepancyJpaEntity(
                UuidV7Generator.generate(), businessDate, entityType, entityId, discrepancyType, detail));
    }

    private enum Outcome {
        MATCHED, MISMATCH, SKIPPED
    }
}
