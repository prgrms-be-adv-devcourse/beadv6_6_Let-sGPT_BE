package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.dto.RefundCompletedPayload;
import com.openat.payment.application.dto.RefundFailedPayload;
import com.openat.payment.application.dto.RefundSettlementSourcePayload;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// E2 — PaymentWebhookHandler와 동일한 모양(Day1 템플릿 재사용). 환불 PG 호출이 타임아웃돼 결과를 못 받은
// 경우를 잡는 보조 채널(환불 PG 호출은 원래도 동기 응답 구조라 A16 영향 없음).
// 토스 실제 웹훅 envelope에는 우리 자체 refundId가 없어(2026-06-24, research.md §17) Payment/WalletCharge와
// 동일하게 paymentKey로 Payment를 찾고, 그 Payment의 PENDING Refund를 역조회한다(plan.md P4).
// I1 — 페이로드의 status는 신뢰하지 않고 tossPaymentClient.queryRefundStatus 조회 결과로 최종 판정한다.
@Slf4j
@Component
public class RefundWebhookHandler extends AbstractPgWebhookHandler<Refund> {

    private static final String COMPLETED_TOPIC = "refund.completed.events";
    private static final String FAILED_TOPIC = "refund.failed.events";
    private static final String SETTLEMENT_SOURCE_TOPIC = "refund.settlement-source.events";

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;
    private final OutboxEventWriter outboxEventWriter;
    private final TossPaymentClient tossPaymentClient;

    public RefundWebhookHandler(List<WebhookOutcomeListener> listeners,
            RefundRepository refundRepository, PaymentRepository paymentRepository, ObjectMapper objectMapper,
            OutboxEventWriter outboxEventWriter, TossPaymentClient tossPaymentClient) {
        super(listeners);
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
        this.tossPaymentClient = tossPaymentClient;
    }

    @Override
    protected boolean checkIdempotency(WebhookRequest request) {
        TossRefundWebhookPayload payload = parse(request);
        if (payload == null) {
            return false;
        }
        return findPendingRefund(payload).isEmpty();
    }

    @Override
    protected UpdateResult<Refund> applyConditionalUpdate(WebhookRequest request) {
        TossRefundWebhookPayload payload = parse(request);
        if (payload == null) {
            return UpdateResult.failure(null, null);
        }
        Optional<Refund> maybeRefund = findPendingRefund(payload);
        if (maybeRefund.isEmpty()) {
            log.warn("[RefundWebhookHandler] 매칭되는 PENDING Refund 없음: paymentKey={}", payload.paymentKey());
            return UpdateResult.failure(null, null);
        }

        Refund refund = maybeRefund.get();

        Optional<Payment> maybePayment = paymentRepository.findById(refund.getPaymentId());
        if (maybePayment.isEmpty()) {
            log.warn("[RefundWebhookHandler] 매칭되는 Payment 없음: paymentId={}", refund.getPaymentId());
            return UpdateResult.failure(null, null);
        }

        // I1 — 페이로드의 status는 트리거 신호로만 쓰고, 실제 판정은 PG 조회 결과로 한다.
        // pgRefundKey가 null(refundPayment 타임아웃 케이스)이면 amount로 매칭하는 폴백을 탄다(plan.md P3).
        TossQueryResult queryResult;
        try {
            queryResult = tossPaymentClient.queryRefundStatus(
                    maybePayment.get().getPgPaymentKey(), refund.getPgRefundKey(), refund.getAmount());
        } catch (Exception e) {
            log.warn("[RefundWebhookHandler] 웹훅 재검증 조회 실패, PENDING 유지: refundId={}", refund.getId(), e);
            return UpdateResult.failure(null, null);
        }

        Refund.Status newStatus =
                queryResult.status() == TossQueryResult.Status.APPROVED ? Refund.Status.COMPLETE : Refund.Status.FAILED;
        LocalDateTime completedAt = newStatus == Refund.Status.COMPLETE ? LocalDateTime.now() : null;

        // 하자드#10과 동일 원칙 — 동기 호출 응답과 거의 동시에 같은 row를 만질 수 있어 조건부 UPDATE로 원자처리.
        int affected = refundRepository.tryTransitionFromPending(
                refund.getId(), newStatus, queryResult.pgTxId(), completedAt);
        if (affected == 0) {
            return UpdateResult.failure(refund.getId(), refund);
        }

        Refund updated = refundRepository.findById(refund.getId()).orElse(refund);
        return newStatus == Refund.Status.COMPLETE
                ? UpdateResult.success(updated.getId(), updated)
                : UpdateResult.failure(updated.getId(), updated);
    }

    @Override
    protected void onSuccess(UpdateResult<Refund> result) {
        Refund refund = result.getPayload();
        Payment payment = paymentRepository.findById(refund.getPaymentId()).orElse(null);
        if (payment == null) {
            return;
        }

        outboxEventWriter.write("REFUND", refund.getId(), COMPLETED_TOPIC, new RefundCompletedPayload(
                refund.getId(), payment.getId(), payment.getOrderId(), refund.getAmount(), refund.getCompletedAt()));

        outboxEventWriter.write("REFUND", refund.getId(), SETTLEMENT_SOURCE_TOPIC, new RefundSettlementSourcePayload(
                refund.getId(), payment.getId(), payment.getOrderId(), payment.getSellerId(), payment.getMemberId(),
                refund.getAmount(), refund.getReason(), Refund.Status.COMPLETE.name(), refund.getCompletedAt()));
    }

    @Override
    protected void onFailure(UpdateResult<Refund> result) {
        Refund refund = result.getPayload();
        if (refund == null) {
            return;
        }
        // PG가 거절을 알려주는 경우 — 환불가능액 한도를 원복(RefundService.failRefund와 동일 원칙).
        paymentRepository.tryDecreaseRefundedAmount(refund.getPaymentId(), refund.getAmount());

        Payment payment = paymentRepository.findById(refund.getPaymentId()).orElse(null);
        UUID orderId = payment == null ? null : payment.getOrderId();
        outboxEventWriter.write("REFUND", refund.getId(), FAILED_TOPIC,
                new RefundFailedPayload(refund.getId(), refund.getPaymentId(), orderId, "PG_REJECTED"));
    }

    @Override
    protected String handlerType() {
        return "REFUND";
    }

    // paymentKey(웹훅) → Payment → 이 Payment의 PENDING Refund 역조회(plan.md P4). 2건 이상이면(가드 없음,
    // research.md §17.2) 가장 오래된(먼저 PENDING이 된) 것을 우선 처리하고 나머지는 경고만 남긴다.
    private Optional<Refund> findPendingRefund(TossRefundWebhookPayload payload) {
        Optional<Payment> payment = paymentRepository.findByPgPaymentKey(payload.paymentKey());
        if (payment.isEmpty()) {
            return Optional.empty();
        }
        List<Refund> pending = refundRepository.findByPaymentIdAndStatus(payment.get().getId(), Refund.Status.PENDING);
        if (pending.size() > 1) {
            log.warn("[RefundWebhookHandler] 동일 Payment에 PENDING Refund가 {}건 — 가장 오래된 것을 처리: paymentId={}",
                    pending.size(), payment.get().getId());
        }
        return pending.stream().min(Comparator.comparing(Refund::getCreatedAt));
    }

    private TossRefundWebhookPayload parse(WebhookRequest request) {
        try {
            return objectMapper.readValue(request.getRawBody(), TossRefundWebhookPayload.Envelope.class).data();
        } catch (Exception e) {
            log.error("[RefundWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
            return null;
        }
    }
}
