package com.openat.payment.infrastructure.client;

import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.client.TossRefundResult;
import java.util.UUID;
import org.springframework.stereotype.Component;

// 실제 토스 confirm 호출 모양이 결정되기 전까지, 항상 승인으로 응답하는 스텁.
// 실 연동 시: Idempotency-Key 헤더 부착(A10) + 실제 HTTP 호출(POST /v1/payments/confirm, 시크릿키 Basic 인증)로 교체, 인터페이스는 그대로 유지.
@Component
public class StubTossPaymentClient implements TossPaymentClient {

    @Override
    public TossConfirmResult confirmPayment(String paymentKey, UUID orderId, Long amount, String idempotencyKey) {
        return TossConfirmResult.approved("stub_pg_tx_" + UUID.randomUUID());
    }

    @Override
    public TossConfirmResult confirmCharge(String paymentKey, UUID chargeId, Long amount, String idempotencyKey) {
        return TossConfirmResult.approved("stub_pg_tx_" + UUID.randomUUID());
    }

    @Override
    public TossRefundResult refundPayment(String pgPaymentKey, Long amount, String idempotencyKey) {
        return TossRefundResult.complete("stub_pg_refund_" + UUID.randomUUID());
    }

    @Override
    public TossQueryResult queryPaymentStatus(String paymentKey) {
        // confirm을 한 번도 호출하지 않은 키는 토스도 모르는 게 현실에 가까움 — NOT_FOUND(=EXPIRED 취급)가 기본값.
        // §3의 "신-하자드9"(confirm은 PG호출까지 갔는데 우리 기록만 끊긴 케이스)를 시연하려면 DB에서
        // pgPaymentKey만 직접 세팅해두고 호출하는 수동 테스트로 확인(day4.md 참고).
        return TossQueryResult.of(TossQueryResult.Status.NOT_FOUND, null);
    }
}
