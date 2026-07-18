package com.openat.payment.infrastructure.webhook;

// Template Method — payments/wallet-charge/refunds 웹훅 3종이 공유하는 파싱→멱등→조건부UPDATE→분기 골격.
// Strategy(PgSignatureVerifier 인터페이스)는 기각(plan.md A: PG 추가 계획 없음).
// 서명검증(TossSignatureVerifier)은 제거(2026-06-24) — 실제 토스 웹훅은 우리 자체 HMAC 헤더를 보내지 않아
// 항상 거부되던 죽은 코드였고(plan.md G3), I1로 PG 재조회(queryPaymentStatus/queryRefundStatus)가 실제
// source of truth가 되면서 페이로드 자체를 신뢰하지 않으므로 서명검증의 역할이 대체됐다.
// Observer(WebhookOutcomeListener)는 제거(7-12 plan WS-E) — 구현체 0개 죽은 확장점이었고, 이벤트 발행 책임이
// application/service의 Finalizer로 이동하며 확장점의 존재 이유가 사라짐(§WS-E, ★검수②).
// P = 파싱된 페이로드, T = applyConditionalUpdate가 다루는 애그리거트.
public abstract class AbstractPgWebhookHandler<P, T> {

  // 웹훅 처리는 @Transactional을 걸지 않는다 — applyConditionalUpdate 안에서 토스 재조회(queryPaymentStatus/
  // queryRefundStatus) HTTP가 일어나는데, 트랜잭션을 걸면 그 왕복 내내 DB 커넥션을 점유한다(confirmPg와 동일 원칙).
  // 대신 "재조회(TX 밖) → 확정(각 Finalizer의 @Transactional)" 2단으로 처리한다: 상태 조회는 커넥션 비점유로 두고,
  // 조건부 UPDATE(affected rows 기준 원자성)는 Finalizer가 자기 짧은 TX에서 수행한다. 오버라이드 대상이 아니므로 final.
  public final WebhookResult handle(WebhookRequest request) {
    P payload =
        parse(request); // 1회만 파싱(종전: checkIdempotency/applyConditionalUpdate 각자 파싱하던 이중화 제거)
    if (payload == null) {
      return WebhookResult.ok(); // 파싱 실패 — 200(재전송 유도 안 함), 로그는 parse가 남김
    }
    if (checkIdempotency(payload)) {
      return WebhookResult.ok();
    }

    WebhookUpdateResult<T> result = applyConditionalUpdate(payload);
    switch (result.outcome()) { // 부수효과 분기가 타입으로 강제됨(§4.1 핵심)
      case APPROVED -> onApproved(result.payload());
      case REJECTED -> onRejected(result.payload());
      case LOST_RACE, NO_MATCH, UNVERIFIABLE -> {
        /* 부수효과 없음 */
      }
    }

    return WebhookResult.ok();
  }

  protected abstract P parse(WebhookRequest request);

  // 같은 이벤트가 재전송됐는지(§4 토스 재전송 정책) 판단 — pgTxId 등 멱등 기준은 구체 핸들러가 정의.
  protected abstract boolean checkIdempotency(P payload);

  // 반드시 단일 조건부 UPDATE(affected rows 기준)로 구현 — SELECT-then-ACT 금지(#10/#13 가드).
  protected abstract WebhookUpdateResult<T> applyConditionalUpdate(P payload);

  protected abstract void onApproved(T entity);

  protected abstract void onRejected(T entity);
}
