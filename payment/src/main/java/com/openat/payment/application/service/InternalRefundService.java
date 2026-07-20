package com.openat.payment.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.InternalRefundResult;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.usecase.RefundUseCase;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// order 주문취소 사가의 환불 진입점(POST /internal/v1/refunds). 유저 직접 호출(RefundController)과 달리
// orderId만 받아 payment 레코드에서 memberId/환불액을 역산하고, 기존 환불 파이프라인(RefundService.requestRefund —
// 접수/확정 TX 분리·조건부 UPDATE·멱등키·TTL 수렴)을 그대로 재사용한다. 도메인 규율은 무변경, orderId 어댑터만 앞에 세운다.
@Service
public class InternalRefundService {

  private final PaymentRepository paymentRepository;
  private final RefundUseCase refundUseCase;

  public InternalRefundService(PaymentRepository paymentRepository, RefundUseCase refundUseCase) {
    this.paymentRepository = paymentRepository;
    this.refundUseCase = refundUseCase;
  }

  public InternalRefundResult refundByOrder(UUID orderId, String idempotencyKey) {
    // [1] 환불 대상 성사분(APPROVED 또는 PARTIALLY_REFUNDED 잔여분) 우선 — 있으면 전액(잔여 전부) 환불 위임.
    Optional<Payment> refundable =
        paymentRepository
            .findByOrderIdAndStatus(orderId, Payment.Status.APPROVED)
            .or(
                () ->
                    paymentRepository.findByOrderIdAndStatus(
                        orderId, Payment.Status.PARTIALLY_REFUNDED));
    if (refundable.isPresent()) {
      return delegateRefund(refundable.get(), idempotencyKey);
    }

    // [2] 이미 전액 환불(REFUNDED) — 멱등 관점에서 접수 재생.
    if (paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.REFUNDED).isPresent()) {
      return InternalRefundResult.REFUND_ACCEPTED;
    }

    // [3] 결제 진행 중(PAYMENT_PENDING) — NO_PAYMENT로 답하면 직후 confirm 성사 시 결제만 살아남는 레이스. 409로 재시도 유도.
    if (paymentRepository
        .findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING)
        .isPresent()) {
      return InternalRefundResult.PAYMENT_PENDING;
    }

    // [4] 결제 성사분 없음(row 없음 또는 전부 PENDING/FAILED/CANCELED).
    return InternalRefundResult.NO_PAYMENT;
  }

  private InternalRefundResult delegateRefund(Payment payment, String idempotencyKey) {
    RefundCommand command =
        new RefundCommand(
            payment.getId(),
            payment.getMemberId(),
            payment.refundableAmount(),
            "주문 취소",
            idempotencyKey);
    try {
      refundUseCase.requestRefund(command);
    } catch (BusinessException e) {
      // 내부 API 멱등 완화 — refund-order-{orderId} 고정 키로 이미 Refund가 존재하는데 그 사이 잔액이 바뀌어
      // requestHash가 달라진 재시도는, 유저용 경로처럼 충돌(IDEMPOTENCY_KEY_CONFLICT)로 막지 않고 접수 재생으로 응답한다.
      if (e.getErrorCode() == PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
        return InternalRefundResult.REFUND_ACCEPTED;
      }
      throw e;
    }
    return InternalRefundResult.REFUND_ACCEPTED;
  }
}
