package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.dto.PaymentFailedPayload;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// PaymentTtlScanner에서 분리(자기호출은 Spring AOP 트랜잭션 프록시를 안 타므로, day3.md의 "final 메서드" 트러블슈팅과
// 같은 종류의 함정 — 같은 클래스 안의 self-invocation으로는 @Transactional이 절대 적용되지 않음).
// 외부 빈으로 분리해 프록시 경계를 넘게 함으로써 회피.
@Component
public class PaymentTtlFinalizer {

    private static final String COMPLETED_TOPIC = "payment.completed.events";
    private static final String FAILED_TOPIC = "payment.failed.events";

    private final PaymentRepository paymentRepository;
    private final OutboxEventWriter outboxEventWriter;

    public PaymentTtlFinalizer(PaymentRepository paymentRepository, OutboxEventWriter outboxEventWriter) {
        this.paymentRepository = paymentRepository;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Transactional
    public void finalizePending(Payment payment, Payment.Status newStatus, String pgTxId, String reason) {
        LocalDateTime approvedAt = newStatus == Payment.Status.APPROVED ? LocalDateTime.now() : null;

        // 하자드10 — confirm 동기응답·보조웹훅과 거의 동시에 같은 row를 만질 수 있어 조건부 UPDATE로 원자처리.
        int affected = paymentRepository.tryTransitionFromPending(payment.getId(), newStatus, pgTxId, approvedAt);
        if (affected == 0) {
            return; // 이미 다른 경로가 먼저 확정함 — 자연히 무시
        }

        Payment updated = paymentRepository.findById(payment.getId()).orElse(payment);
        if (newStatus == Payment.Status.APPROVED) {
            outboxEventWriter.write("PAYMENT", updated.getId(), COMPLETED_TOPIC, new PaymentCompletedPayload(
                    updated.getId(), updated.getOrderId(), updated.getMemberId(), updated.getAmount(),
                    updated.getMethod().name(), updated.getPgTxId(), updated.getApprovedAt()));
        } else {
            outboxEventWriter.write("PAYMENT", updated.getId(), FAILED_TOPIC,
                    new PaymentFailedPayload(updated.getId(), updated.getOrderId(), reason));
        }
    }
}
