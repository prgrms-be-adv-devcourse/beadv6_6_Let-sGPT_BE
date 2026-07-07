package com.openat.payment.infrastructure.webhook;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

// Template Method — payments/wallet-charge/refunds 웹훅 3종이 공유하는 멱등→조건부UPDATE→분기 골격.
// Strategy(PgSignatureVerifier 인터페이스)는 기각(plan.md A: PG 추가 계획 없음).
// 서명검증(TossSignatureVerifier)은 제거(2026-06-24) — 실제 토스 웹훅은 우리 자체 HMAC 헤더를 보내지 않아
// 항상 거부되던 죽은 코드였고(plan.md G3), I1로 PG 재조회(queryPaymentStatus/queryRefundStatus)가 실제
// source of truth가 되면서 페이로드 자체를 신뢰하지 않으므로 서명검증의 역할이 대체됐다.
// Observer(WebhookOutcomeListener)는 유지 — outbox 적재 등 후속처리가 늘어나도 본 클래스는 안 건드림.
public abstract class AbstractPgWebhookHandler<T> {

    private final List<WebhookOutcomeListener> listeners;

    protected AbstractPgWebhookHandler(List<WebhookOutcomeListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    // final이면 Spring의 CGLIB 프록시가 이 메서드를 오버라이드할 수 없어 @Transactional이 적용되지 않음
    // (트랜잭션 없이 @Modifying 쿼리가 실행돼 TransactionRequiredException) — final 제거, 서브클래스는 여전히 오버라이드 안 함.
    @Transactional
    public WebhookResult handle(WebhookRequest request) {
        if (checkIdempotency(request)) {
            return WebhookResult.ok();
        }

        UpdateResult<T> result = applyConditionalUpdate(request);
        if (result.isSuccess()) {
            onSuccess(result);
        } else {
            onFailure(result);
        }
        notifyListeners(result);

        return WebhookResult.ok();
    }

    // 같은 이벤트가 재전송됐는지(§4 토스 재전송 정책) 판단 — pgTxId 등 멱등 기준은 구체 핸들러가 정의.
    protected abstract boolean checkIdempotency(WebhookRequest request);

    // 반드시 단일 조건부 UPDATE(affected rows 기준)로 구현 — SELECT-then-ACT 금지(#10/#13 가드).
    protected abstract UpdateResult<T> applyConditionalUpdate(WebhookRequest request);

    protected abstract void onSuccess(UpdateResult<T> result);

    protected abstract void onFailure(UpdateResult<T> result);

    // 리스너 통지용 라벨(예: "PAYMENT"/"WALLET_CHARGE"/"REFUND").
    protected abstract String handlerType();

    private void notifyListeners(UpdateResult<T> result) {
        WebhookOutcome outcome = new WebhookOutcome(handlerType(), result.isSuccess(), result.getReferenceId());
        listeners.forEach(listener -> listener.onOutcome(outcome));
    }
}
