package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.InternalPaymentStatusResult;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// order의 TTL 만료 후 결제 결과 확인용 pull 경로(GET /internal/v1/payments?orderId=).
// orderId당 복수 시도 row가 있을 수 있어(결제 재시도 시 PENDING 다건), "가장 의미 있는 1건"을 고른다:
// 결제 성사분(APPROVED/PARTIALLY_REFUNDED/REFUNDED)을 우선하고, 없으면 최신 1건(findByOrderId) 폴백. 둘 다 없으면 404.
@Service
public class InternalPaymentQueryService {

  private final PaymentRepository paymentRepository;

  public InternalPaymentQueryService(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  public InternalPaymentStatusResult getByOrderId(UUID orderId) {
    Payment payment =
        settledPayment(orderId)
            .or(() -> paymentRepository.findByOrderId(orderId))
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    return new InternalPaymentStatusResult(
        payment.getId(), payment.getStatus().name(), payment.getAmount());
  }

  // 결제 성사분 우선 조회 — APPROVED -> PARTIALLY_REFUNDED -> REFUNDED 순.
  private Optional<Payment> settledPayment(UUID orderId) {
    return paymentRepository
        .findByOrderIdAndStatus(orderId, Payment.Status.APPROVED)
        .or(
            () ->
                paymentRepository.findByOrderIdAndStatus(
                    orderId, Payment.Status.PARTIALLY_REFUNDED))
        .or(() -> paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.REFUNDED));
  }
}
