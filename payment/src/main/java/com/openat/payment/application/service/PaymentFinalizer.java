package com.openat.payment.application.service;

import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.dto.PaymentFailedPayload;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 확정(finalize) 전담 — confirm 동기응답/보조 웹훅/TTL스캐너 3경로가 전부 이걸 호출한다(7-12 plan WS-D, code_review §5.1).
// 하자드10 — 조건부 UPDATE로 전이 경합을 원자 판정, "이긴 경로만" 이벤트를 발행한다.
// §4.1 결함 수정 지점 — lost-race(affected==0)는 Optional.empty로 타입 수준에서 "부수효과 없음"을 강제한다.
@Component
public class PaymentFinalizer {

  private static final String COMPLETED_TOPIC = "payment.completed.events";
  private static final String FAILED_TOPIC = "payment.failed.events";

  private final PaymentRepository paymentRepository;
  private final DomainEventPublisher eventPublisher;

  public PaymentFinalizer(
      PaymentRepository paymentRepository, DomainEventPublisher eventPublisher) {
    this.paymentRepository = paymentRepository;
    this.eventPublisher = eventPublisher;
  }

  // @return 전이에 이겼으면 확정된 Payment, 졌으면(다른 경로가 선확정) Optional.empty
  @Transactional
  public Optional<Payment> finalizePending(
      UUID paymentId, Payment.Status newStatus, String pgTxId, String failReason) {
    if (!Payment.Status.PAYMENT_PENDING.canTransitionTo(newStatus)) { // WS-C 1차 방어선
      throw new IllegalStateException("불법 전이: PAYMENT_PENDING -> " + newStatus);
    }
    LocalDateTime approvedAt = newStatus == Payment.Status.APPROVED ? LocalDateTime.now() : null;
    int affected =
        paymentRepository.tryTransitionFromPending(paymentId, newStatus, pgTxId, approvedAt);
    if (affected == 0) {
      return Optional.empty(); // lost-race — 부수효과 없음이 타입으로 보장됨(§4.1 해결 지점)
    }
    Payment updated =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(
                () -> new IllegalStateException("전이 직후 Payment 소실: " + paymentId)); // 실패는 시끄럽게
    if (newStatus == Payment.Status.APPROVED) {
      eventPublisher.publish(
          "PAYMENT",
          updated.getId(),
          COMPLETED_TOPIC,
          new PaymentCompletedPayload(
              updated.getId(),
              updated.getOrderId(),
              updated.getMemberId(),
              updated.getAmount(),
              updated.getMethod().name(),
              updated.getPgTxId(),
              updated.getApprovedAt()));
    } else {
      eventPublisher.publish(
          "PAYMENT",
          updated.getId(),
          FAILED_TOPIC,
          new PaymentFailedPayload(updated.getId(), updated.getOrderId(), failReason));
    }
    return Optional.of(updated);
  }
}
