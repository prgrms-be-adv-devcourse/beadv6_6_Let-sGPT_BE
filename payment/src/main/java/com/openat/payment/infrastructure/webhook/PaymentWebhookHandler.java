package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.dto.PaymentFailedPayload;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Day1 템플릿(AbstractPgWebhookHandler)의 첫 구체 구현 — payment_domain_diagrams.md §2.
// 토스 실제 웹훅 envelope은 아직 미확정(qna.md 논의 보류) — 우리 쪽 계약(api_event_specification.md)대로 우선 구현,
// ngrok 실연동 시 페이로드 파싱 부분만 교체하면 됨.
// I1 — 페이로드의 status는 신뢰하지 않고 tossPaymentClient.queryPaymentStatus 조회 결과로 최종 판정한다.
@Slf4j
@Component
public class PaymentWebhookHandler extends AbstractPgWebhookHandler<Payment> {

    private static final String COMPLETED_TOPIC = "payment.completed.events";
    private static final String FAILED_TOPIC = "payment.failed.events";

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;
    private final OutboxEventWriter outboxEventWriter;
    private final TossPaymentClient tossPaymentClient;

    public PaymentWebhookHandler(List<WebhookOutcomeListener> listeners,
            PaymentRepository paymentRepository, ObjectMapper objectMapper, OutboxEventWriter outboxEventWriter,
            TossPaymentClient tossPaymentClient) {
        super(listeners);
        this.paymentRepository = paymentRepository;
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
        this.tossPaymentClient = tossPaymentClient;
    }

    @Override
    protected boolean checkIdempotency(WebhookRequest request) {
        TossPaymentWebhookPayload payload = parse(request);
        if (payload == null) {
            return false;
        }
        Optional<Payment> payment = paymentRepository.findByPgPaymentKey(payload.paymentKey());
        // 이미 PAYMENT_PENDING을 벗어난 row면 재전송(§4 토스 재전송 정책)으로 판단 — 재처리하지 않음.
        return payment.isPresent() && payment.get().getStatus() != Payment.Status.PAYMENT_PENDING;
    }

    @Override
    protected UpdateResult<Payment> applyConditionalUpdate(WebhookRequest request) {
        TossPaymentWebhookPayload payload = parse(request);
        if (payload == null) {
            return UpdateResult.failure(null, null);
        }
        Optional<Payment> maybePayment = paymentRepository.findByPgPaymentKey(payload.paymentKey());
        if (maybePayment.isEmpty()) {
            // 하자드#3 — 웹훅이 PENDING row보다 먼저 도착한 극단 케이스. 세미 범위 밖(research.md §10.3 #3) — 그대로 실패 처리.
            log.warn("[PaymentWebhookHandler] 매칭되는 Payment 없음: paymentKey={}", payload.paymentKey());
            return UpdateResult.failure(null, null);
        }

        Payment payment = maybePayment.get();

        // I1 — 페이로드의 status는 "지금 뭔가 바뀌었다"는 트리거 신호로만 쓰고, 실제 판정은 PG 조회 결과로 한다.
        TossQueryResult queryResult;
        try {
            queryResult = tossPaymentClient.queryPaymentStatus(payload.paymentKey());
        } catch (Exception e) {
            // 조회 실패(타임아웃/네트워크 오류) — 강제로 닫지 않고 PENDING 유지, TTL스캐너 다음 주기 재시도에 위임.
            log.warn("[PaymentWebhookHandler] 웹훅 재검증 조회 실패, PENDING 유지: paymentId={}", payment.getId(), e);
            return UpdateResult.failure(null, null);
        }

        Payment.Status newStatus =
                queryResult.status() == TossQueryResult.Status.APPROVED ? Payment.Status.APPROVED : Payment.Status.FAILED;
        LocalDateTime approvedAt = newStatus == Payment.Status.APPROVED ? LocalDateTime.now() : null;

        // 하자드#10 — TTL스캐너(Day4)와 동시에 같은 row를 만질 수 있어 단일 조건부 UPDATE로 원자처리.
        int affected = paymentRepository.tryTransitionFromPending(
                payment.getId(), newStatus, queryResult.pgTxId(), approvedAt);
        if (affected == 0) {
            return UpdateResult.failure(payment.getId(), payment);
        }

        Payment updated = paymentRepository.findById(payment.getId()).orElse(payment);
        return newStatus == Payment.Status.APPROVED
                ? UpdateResult.success(updated.getId(), updated)
                : UpdateResult.failure(updated.getId(), updated);
    }

    @Override
    protected void onSuccess(UpdateResult<Payment> result) {
        Payment payment = result.getPayload();
        outboxEventWriter.write("PAYMENT", payment.getId(), COMPLETED_TOPIC, new PaymentCompletedPayload(
                payment.getId(), payment.getOrderId(), payment.getMemberId(), payment.getAmount(),
                payment.getMethod().name(), payment.getPgTxId(), payment.getApprovedAt()));
    }

    @Override
    protected void onFailure(UpdateResult<Payment> result) {
        Payment payment = result.getPayload();
        if (payment == null) {
            return;
        }
        // 보조 채널(웹훅)이 거절을 알려주는 경우는 PG가 직접 거절한 사유(하자드#23 — EXPIRED는 confirm 경로에서만 발생).
        outboxEventWriter.write("PAYMENT", payment.getId(), FAILED_TOPIC,
                new PaymentFailedPayload(payment.getId(), payment.getOrderId(), "PG_REJECTED"));
    }

    @Override
    protected String handlerType() {
        return "PAYMENT";
    }

    private TossPaymentWebhookPayload parse(WebhookRequest request) {
        try {
            return objectMapper.readValue(request.getRawBody(), TossPaymentWebhookPayload.Envelope.class).data();
        } catch (Exception e) {
            log.error("[PaymentWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
            return null;
        }
    }
}
