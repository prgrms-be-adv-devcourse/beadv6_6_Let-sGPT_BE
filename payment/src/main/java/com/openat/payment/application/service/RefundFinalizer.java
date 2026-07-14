package com.openat.payment.application.service;

import com.openat.payment.application.dto.RefundCompletedPayload;
import com.openat.payment.application.dto.RefundFailedPayload;
import com.openat.payment.application.dto.RefundSettlementSourcePayload;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 확정 전담(PaymentFinalizer와 동일 원칙, 7-12 plan WS-D) — RefundService 동기응답/RefundWebhookHandler 보조채널
// 2경로가 전부 이걸 호출한다. fail이 이긴 경우에만 환불한도 원복(§4.1 환불 결함 수정 지점, 여기 한 곳으로 수렴).
@Component
public class RefundFinalizer {

  private static final String COMPLETED_TOPIC = "refund.completed.events";
  private static final String FAILED_TOPIC = "refund.failed.events";
  private static final String SETTLEMENT_SOURCE_TOPIC = "payment.settlement.events";
  private static final String SETTLEMENT_EVENT_TYPE = "RefundSettlementCompleted";

  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final DomainEventPublisher eventPublisher;

  public RefundFinalizer(
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      DomainEventPublisher eventPublisher) {
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public Optional<Refund> complete(UUID refundId, Payment payment, String pgRefundKey) {
    LocalDateTime completedAt = LocalDateTime.now();
    int affected =
        refundRepository.tryTransitionFromPending(
            refundId, Refund.Status.COMPLETE, pgRefundKey, completedAt);
    if (affected == 0) {
      return Optional.empty(); // lost-race — 부수효과 없음
    }
    Refund updated =
        refundRepository
            .findById(refundId)
            .orElseThrow(() -> new IllegalStateException("전이 직후 Refund 소실: " + refundId));

    eventPublisher.publish(
        "REFUND",
        updated.getId(),
        COMPLETED_TOPIC,
        new RefundCompletedPayload(
            updated.getId(),
            payment.getId(),
            payment.getOrderId(),
            updated.getAmount(),
            updated.getCompletedAt()));

    // B6/A6 — sellerId가 사후채움 전이라 null이어도 그대로 발행(정산 쪽 보류/재시도 전제, plan.md B6).
    eventPublisher.publish(
        "REFUND",
        updated.getId(),
        SETTLEMENT_SOURCE_TOPIC,
        new RefundSettlementSourcePayload(
            UUID.randomUUID().toString(),
            SETTLEMENT_EVENT_TYPE,
            LocalDateTime.now(),
            updated.getId(),
            payment.getId(),
            payment.getOrderId(),
            payment.getSellerId(),
            payment.getMemberId(),
            updated.getAmount(),
            updated.getReason(),
            Refund.Status.COMPLETE.name(),
            updated.getCompletedAt()));

    return Optional.of(updated);
  }

  @Transactional
  public Optional<Refund> fail(UUID refundId, Payment payment, String reason) {
    int affected =
        refundRepository.tryTransitionFromPending(refundId, Refund.Status.FAILED, null, null);
    if (affected == 0) {
      return Optional.empty(); // lost-race — 한도 원복도, 이벤트도 발생하지 않는다(§4.1 결함 수정 지점)
    }
    Refund updated =
        refundRepository
            .findById(refundId)
            .orElseThrow(() -> new IllegalStateException("전이 직후 Refund 소실: " + refundId));

    // PG가 명시적으로 거절했으므로 한도 보정(원복) — lost-race는 여기 안 온다.
    paymentRepository.tryDecreaseRefundedAmount(payment.getId(), updated.getAmount());

    eventPublisher.publish(
        "REFUND",
        updated.getId(),
        FAILED_TOPIC,
        new RefundFailedPayload(updated.getId(), payment.getId(), payment.getOrderId(), reason));

    return Optional.of(updated);
  }
}
