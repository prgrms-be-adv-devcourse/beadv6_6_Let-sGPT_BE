package com.openat.payment.infrastructure.client;

import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossPaymentDetail;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.client.TossRefundResult;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 항상 승인으로 응답하는 스텁 — localtest/compose 프로필에서 실제 PG 호출 없이 동작.
// 실제 연동은 RealTossPaymentClient로 분리, real 프로필 활성화 시에만 그쪽으로 교체.
@Component
@Profile("!real")
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

    @Override
    public TossPaymentDetail queryPaymentDetail(String pgPaymentKey) {
        // PG 대사(WS-0) 스텁 — 실제 PG가 없는 환경이라 금액은 모른다(totalAmount=null → 호출측이 상태만으로 판정).
        // 상태는 항상 APPROVED로 응답해 local/compose 데모에서 대사가 자연스럽게 MATCHED로 수렴하게 한다.
        return TossPaymentDetail.of(TossPaymentDetail.Status.APPROVED, null, "stub_pg_tx_" + UUID.randomUUID());
    }

    @Override
    public TossQueryResult queryRefundStatus(String pgPaymentKey, String pgRefundKey, Long amount) {
        // I1 — 항상 승인된 취소로 응답하는 스텁(단위테스트 기본 경로). 거절/미존재 케이스는 RefundWebhookHandlerTest에서
        // Mockito mock으로 직접 오버라이드해서 검증(이 클래스는 실제 빈 와이어링 확인용이라 분기 시연은 안 함).
        return TossQueryResult.of(TossQueryResult.Status.APPROVED, "stub_pg_refund_tx_" + UUID.randomUUID());
    }
}
