package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.dto.RefundCompletedPayload;
import com.openat.payment.application.dto.RefundFailedPayload;
import com.openat.payment.application.dto.RefundSettlementSourcePayload;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// E2 — PaymentWebhookHandler와 동일한 모양(Day1 템플릿 재사용). 환불 PG 호출이 타임아웃돼 결과를 못 받은
// 경우를 잡는 보조 채널(환불 PG 호출은 원래도 동기 응답 구조라 A16 영향 없음). refundId가 payload에 직접
// 포함돼 있어 결제/충전 웹훅과 달리 pgRefundKeyHash 같은 해시 매칭이 필요 없음(findById로 바로 매칭).
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

    public RefundWebhookHandler(TossSignatureVerifier signatureVerifier, List<WebhookOutcomeListener> listeners,
            RefundRepository refundRepository, PaymentRepository paymentRepository, ObjectMapper objectMapper,
            OutboxEventWriter outboxEventWriter) {
        super(signatureVerifier, listeners);
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    protected boolean checkIdempotency(WebhookRequest request) {
        TossRefundWebhookPayload payload = parse(request);
        if (payload == null) {
            return false;
        }
        Optional<Refund> refund = findRefund(payload);
        return refund.isPresent() && refund.get().getStatus() != Refund.Status.PENDING;
    }

    @Override
    protected UpdateResult<Refund> applyConditionalUpdate(WebhookRequest request) {
        TossRefundWebhookPayload payload = parse(request);
        if (payload == null) {
            return UpdateResult.failure(null, null);
        }
        Optional<Refund> maybeRefund = findRefund(payload);
        if (maybeRefund.isEmpty()) {
            log.warn("[RefundWebhookHandler] 매칭되는 Refund 없음: refundId={}", payload.refundId());
            return UpdateResult.failure(null, null);
        }

        Refund refund = maybeRefund.get();
        Refund.Status newStatus = "DONE".equals(payload.status()) ? Refund.Status.COMPLETE : Refund.Status.FAILED;
        LocalDateTime completedAt = newStatus == Refund.Status.COMPLETE ? LocalDateTime.now() : null;

        // 하자드#10과 동일 원칙 — 동기 호출 응답과 거의 동시에 같은 row를 만질 수 있어 조건부 UPDATE로 원자처리.
        int affected = refundRepository.tryTransitionFromPending(
                refund.getId(), newStatus, payload.refundKey(), completedAt);
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

    private Optional<Refund> findRefund(TossRefundWebhookPayload payload) {
        try {
            return refundRepository.findById(UUID.fromString(payload.refundId()));
        } catch (IllegalArgumentException e) {
            log.error("[RefundWebhookHandler] refundId 파싱 실패: {}", payload.refundId(), e);
            return Optional.empty();
        }
    }

    private TossRefundWebhookPayload parse(WebhookRequest request) {
        try {
            return objectMapper.readValue(request.getRawBody(), TossRefundWebhookPayload.class);
        } catch (Exception e) {
            log.error("[RefundWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
            return null;
        }
    }
}
