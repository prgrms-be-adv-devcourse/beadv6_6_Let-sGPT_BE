package com.openat.settlement.application.service;

import com.openat.settlement.application.dto.DailyReconciliationSummary;
import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementRefund;
import com.openat.settlement.domain.repository.SettlementOrderRepository;
import com.openat.settlement.domain.repository.SettlementRefundRepository;
import com.openat.settlement.infrastructure.client.PaymentReconciliationClient;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse.PaymentItem;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse.RefundItem;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationDiscrepancyJpaEntity;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationDiscrepancyJpaEntity.DiscrepancyType;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationDiscrepancyJpaEntity.EntityType;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationDiscrepancyJpaRepository;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaEntity;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaEntity.Status;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaRepository;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 정산 대사(personal_workplan/plan/reconciliation.md 실행 흐름, WS-3) — payment 일별 API를 pull해서
// settlement_orders/settlement_refunds와 대조·기록만 한다(재적재 안 함, upsert는 기존 Kafka 경로 소유).
// PG 대사(payment WS-0)가 먼저 돌아 MATCHED 행만 이 API에 노출된다는 전제 — 이 서비스는 "전달됐는가"만 검증한다.
// reconcile()에 @Transactional 필요 — resultRepository/discrepancyRepository의 save는 SimpleJpaRepository라
// 자체 트랜잭션을 열지만, 같은 businessDate 재실행 시 deleteByBusinessDate + 재삽입을 원자적으로 묶으려면
// 상위 트랜잭션이 있어야 한다(실기동 검증 — 트랜잭션 없이 커스텀 삭제/저장을 섞으면 부분 반영 위험 확인).
@Slf4j
@Service
public class DailyReconciliationService {

    private final PaymentReconciliationClient paymentReconciliationClient;
    private final SettlementOrderRepository settlementOrderRepository;
    private final SettlementRefundRepository settlementRefundRepository;
    private final DailyReconciliationResultJpaRepository resultRepository;
    private final DailyReconciliationDiscrepancyJpaRepository discrepancyRepository;

    public DailyReconciliationService(PaymentReconciliationClient paymentReconciliationClient,
            SettlementOrderRepository settlementOrderRepository, SettlementRefundRepository settlementRefundRepository,
            DailyReconciliationResultJpaRepository resultRepository,
            DailyReconciliationDiscrepancyJpaRepository discrepancyRepository) {
        this.paymentReconciliationClient = paymentReconciliationClient;
        this.settlementOrderRepository = settlementOrderRepository;
        this.settlementRefundRepository = settlementRefundRepository;
        this.resultRepository = resultRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    @Transactional
    public DailyReconciliationSummary reconcile(LocalDate businessDate) {
        DailyPaymentSettlementResponse response;
        try {
            response = paymentReconciliationClient.getDaily(businessDate);
        } catch (Exception e) {
            log.error("[DailyReconciliationService] payment 일별 API 호출 실패, 관리자 확인 필요: businessDate={}",
                    businessDate, e);
            saveFailedResult(businessDate);
            return new DailyReconciliationSummary(businessDate.toString(), Status.CALL_FAILED.name(), 0, 0, 0);
        }

        validateBasicIntegrity(businessDate, response);

        // 같은 businessDate 재실행(hold 재유입, WS-0.5) 시 이전 불일치 기록을 지우고 다시 채운다 — 재실행마다
        // 중복 누적되지 않게(재-pull이 멱등하도록).
        discrepancyRepository.deleteByBusinessDate(businessDate);

        int discrepancyCount = 0;
        for (PaymentItem item : response.payments()) {
            if (!checkPayment(businessDate, item)) {
                discrepancyCount++;
            }
        }
        for (RefundItem item : response.refunds()) {
            if (!checkRefund(businessDate, item)) {
                discrepancyCount++;
            }
        }

        Status status = discrepancyCount == 0 ? Status.SUCCESS : Status.DISCREPANCY_FOUND;
        if (status == Status.DISCREPANCY_FOUND) {
            log.error("[DailyReconciliationService] 불일치 {}건 발견, 관리자 확인 필요: businessDate={}",
                    discrepancyCount, businessDate);
        }
        saveResult(businessDate, status, response, discrepancyCount);
        return new DailyReconciliationSummary(businessDate.toString(), status.name(),
                response.payments().size(), response.refunds().size(), discrepancyCount);
    }

    // 기본 검증(reconciliation.md "응답 데이터 기본 검증") — summary 카운트/금액이 배열과 어긋나면 경고만 남기고 계속 진행.
    private void validateBasicIntegrity(LocalDate businessDate, DailyPaymentSettlementResponse response) {
        if (response.summary().paymentCount() != response.payments().size()
                || response.summary().refundCount() != response.refunds().size()) {
            log.warn("[DailyReconciliationService] summary 카운트가 배열 길이와 불일치: businessDate={}", businessDate);
        }
        long expected = response.summary().totalPaymentAmount() - response.summary().totalRefundAmount();
        if (expected != response.summary().expectedSettlementAmount()) {
            log.warn("[DailyReconciliationService] expectedSettlementAmount 재계산 불일치: businessDate={}", businessDate);
        }
    }

    // true=정상(일치), false=불일치 기록됨.
    private boolean checkPayment(LocalDate businessDate, PaymentItem item) {
        boolean ok = true;
        if (item.sellerId() == null || item.productId() == null) {
            recordDiscrepancy(businessDate, EntityType.ORDER, item.orderId(),
                    DiscrepancyType.NULL_SELLER, "sellerId/productId 미채움(order_completed 사후채움 전)");
            ok = false;
        }

        Optional<SettlementOrder> existing = settlementOrderRepository.findByOrderId(item.orderId());
        if (existing.isEmpty()) {
            recordDiscrepancy(businessDate, EntityType.ORDER, item.orderId(),
                    DiscrepancyType.MISSING_IN_SETTLEMENT, "settlement_orders에 없음(이벤트 유실/지연 의심)");
            return false;
        }

        SettlementOrder order = existing.get();
        if (!Objects.equals(order.getPaidAmount(), item.paidAmount())) {
            recordDiscrepancy(businessDate, EntityType.ORDER, item.orderId(),
                    DiscrepancyType.AMOUNT_MISMATCH,
                    "settlement paidAmount=" + order.getPaidAmount() + ", payment paidAmount=" + item.paidAmount());
            ok = false;
        }
        return ok;
    }

    private boolean checkRefund(LocalDate businessDate, RefundItem item) {
        Optional<SettlementRefund> existing = settlementRefundRepository.findByRefundId(item.refundId());
        if (existing.isEmpty()) {
            recordDiscrepancy(businessDate, EntityType.REFUND, item.refundId(),
                    DiscrepancyType.MISSING_IN_SETTLEMENT, "settlement_refunds에 없음(이벤트 유실/지연 의심)");
            return false;
        }

        SettlementRefund refund = existing.get();
        if (!Objects.equals(refund.getRefundAmount(), item.refundAmount())) {
            recordDiscrepancy(businessDate, EntityType.REFUND, item.refundId(),
                    DiscrepancyType.AMOUNT_MISMATCH,
                    "settlement refundAmount=" + refund.getRefundAmount() + ", payment refundAmount=" + item.refundAmount());
            return false;
        }
        return true;
    }

    private void recordDiscrepancy(LocalDate businessDate, EntityType entityType, java.util.UUID referenceId,
            DiscrepancyType discrepancyType, String detail) {
        discrepancyRepository.save(new DailyReconciliationDiscrepancyJpaEntity(
                businessDate, entityType, referenceId, discrepancyType, detail));
    }

    private void saveResult(LocalDate businessDate, Status status, DailyPaymentSettlementResponse response,
            int discrepancyCount) {
        int paymentCount = response.payments().size();
        int refundCount = response.refunds().size();
        long totalPaymentAmount = response.summary().totalPaymentAmount();
        long totalRefundAmount = response.summary().totalRefundAmount();
        long expectedSettlementAmount = response.summary().expectedSettlementAmount();

        // 같은 businessDate 재-pull(hold 재유입, WS-0.5) 시 이전 결과를 덮어쓴다 — 미수렴 날짜의 catch-up 재시도.
        Optional<DailyReconciliationResultJpaEntity> existing = resultRepository.findByBusinessDate(businessDate);
        if (existing.isPresent()) {
            existing.get().overwriteWith(status, paymentCount, totalPaymentAmount, refundCount, totalRefundAmount,
                    expectedSettlementAmount, discrepancyCount);
            resultRepository.save(existing.get());
            return;
        }
        resultRepository.save(new DailyReconciliationResultJpaEntity(businessDate, status, paymentCount,
                totalPaymentAmount, refundCount, totalRefundAmount, expectedSettlementAmount, discrepancyCount));
    }

    private void saveFailedResult(LocalDate businessDate) {
        Optional<DailyReconciliationResultJpaEntity> existing = resultRepository.findByBusinessDate(businessDate);
        if (existing.isPresent()) {
            existing.get().overwriteWith(Status.CALL_FAILED, 0, 0L, 0, 0L, 0L, 0);
            resultRepository.save(existing.get());
            return;
        }
        resultRepository.save(new DailyReconciliationResultJpaEntity(
                businessDate, Status.CALL_FAILED, 0, 0L, 0, 0L, 0L, 0));
    }
}
