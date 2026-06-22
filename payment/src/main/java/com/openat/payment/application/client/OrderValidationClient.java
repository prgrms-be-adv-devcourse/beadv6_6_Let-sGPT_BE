package com.openat.payment.application.client;

import java.util.UUID;

// 결제 요청 접수 시 브라우저 제출값(orderId/amount)을 그대로 신뢰하지 않고 Order의 진짜 값과 대조하는 포트(#17, B5).
// B5(GET /internal/v1/orders/{orderId})가 아직 주문팀에서 내려오지 않아, 지금은 StubOrderValidationClient만 존재.
public interface OrderValidationClient {

    OrderValidationResult validate(UUID orderId, UUID claimedMemberId, Long claimedAmount);
}
