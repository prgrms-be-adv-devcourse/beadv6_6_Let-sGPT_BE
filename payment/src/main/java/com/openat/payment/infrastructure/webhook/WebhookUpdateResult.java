package com.openat.payment.infrastructure.webhook;

import java.util.UUID;

// applyConditionalUpdate() 결과 — "failure" 하나에 파싱실패/매칭없음/lost-race/정식거절이 과적되어
// lost-race에 거절 부수효과가 실행되던 §4.1 결함을 Outcome으로 타입 분리해 재발 방지(7-12 plan WS-E).
public record WebhookUpdateResult<T>(Outcome outcome, UUID referenceId, T payload) {

  public enum Outcome {
    APPROVED, // 전이 승리 + 승인 — 완료 부수효과 실행
    REJECTED, // 전이 승리 + PG 정식 거절 — 실패 부수효과 실행(이벤트/한도원복)
    LOST_RACE, // 조건부 UPDATE 패배 — 다른 경로가 먼저 확정, 아무것도 하지 않는다
    NO_MATCH, // 매칭 row 없음(하자드#3 등) — 무시(200)
    UNVERIFIABLE // 파싱 실패·PG 재검증 조회 실패 — PENDING 유지, TTL스캐너에 위임
  }

  public static <T> WebhookUpdateResult<T> approved(UUID referenceId, T payload) {
    return new WebhookUpdateResult<>(Outcome.APPROVED, referenceId, payload);
  }

  public static <T> WebhookUpdateResult<T> rejected(UUID referenceId, T payload) {
    return new WebhookUpdateResult<>(Outcome.REJECTED, referenceId, payload);
  }

  public static <T> WebhookUpdateResult<T> lostRace(UUID referenceId) {
    return new WebhookUpdateResult<>(Outcome.LOST_RACE, referenceId, null);
  }

  public static <T> WebhookUpdateResult<T> noMatch() {
    return new WebhookUpdateResult<>(Outcome.NO_MATCH, null, null);
  }

  public static <T> WebhookUpdateResult<T> unverifiable() {
    return new WebhookUpdateResult<>(Outcome.UNVERIFIABLE, null, null);
  }
}
