package com.openat.payment.application.client;

import java.util.UUID;

// PG 결제 승인(payment_domain_diagrams.md §2, v3) — confirm이 결제 승인의 유일한 트리거(토스 API상 사실).
// 브라우저의 토스 SDK가 PG를 직접 호출해 발급한 paymentKey를 confirm 요청으로 전달받아, 이 인터페이스로 토스 승인 API를 동기 호출한다.
public interface TossPaymentClient {

    TossConfirmResult confirmPayment(String paymentKey, UUID orderId, Long amount, String idempotencyKey);

    // PG 상태조회(§3 TTL 스캐너) — confirm을 못 받고 PAYMENT_PENDING에 머무는 row를 강제로 확정할 때 사용.
    TossQueryResult queryPaymentStatus(String paymentKey);
}
