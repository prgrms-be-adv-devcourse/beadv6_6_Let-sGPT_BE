package com.openat.payment.application.client;

import java.util.UUID;

// PG 결제 승인(payment_domain_diagrams.md §2, v3) — confirm이 결제 승인의 유일한 트리거(토스 API상 사실).
// 브라우저의 토스 SDK가 PG를 직접 호출해 발급한 paymentKey를 confirm 요청으로 전달받아, 이 인터페이스로 토스 승인 API를 동기 호출한다.
public interface TossPaymentClient {

    TossConfirmResult confirmPayment(String paymentKey, UUID orderId, Long amount, String idempotencyKey);

    // 충전 PG 승인(E1, plan.md E1-1) — 결제 confirm과 동일한 토스 API를 호출하지만, 참조키 의미(orderId vs chargeId)가
    // 달라 인터페이스 계약을 명확히 하기 위해 별도 메서드로 분리(구현체 내부 HTTP 호출은 공유 가능).
    TossConfirmResult confirmCharge(String paymentKey, UUID chargeId, Long amount, String idempotencyKey);

    // PG 상태조회(§3 TTL 스캐너) — confirm을 못 받고 PAYMENT_PENDING에 머무는 row를 강제로 확정할 때 사용.
    TossQueryResult queryPaymentStatus(String paymentKey);

    // 환불(결제취소, E2) — PG 환불 호출에도 멱등키 부착(#12).
    TossRefundResult refundPayment(String pgPaymentKey, Long amount, String idempotencyKey);
}
