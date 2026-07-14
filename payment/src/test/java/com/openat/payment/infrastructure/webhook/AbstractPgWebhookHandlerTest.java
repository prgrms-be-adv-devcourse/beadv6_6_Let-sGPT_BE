package com.openat.payment.infrastructure.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

// 프레임워크(Spring) 의존 없이 템플릿 골격(파싱→멱등→조건부UPDATE→분기)만 검증하는 순수 단위테스트(A13 목표).
// Observer(WebhookOutcomeListener)는 제거됨(7-12 plan WS-E, ★검수②) — 확정 이벤트 발행은 application/service의
// Finalizer로 이동. 서명검증 단계도 제거됨(2026-06-24, I1) — PG 재조회가 source of truth.
class AbstractPgWebhookHandlerTest {

  @Test
  void 파싱이_실패하면_이후_단계_없이_200을_반환한다() {
    FakePgWebhookHandler handler = new FakePgWebhookHandler();
    handler.parsedPayload = null;

    WebhookResult result = handler.handle(new WebhookRequest("body"));

    assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(handler.checkIdempotencyCalled).isFalse();
    assertThat(handler.applyConditionalUpdateCalled).isFalse();
  }

  @Test
  void 이미_처리된_이벤트는_재처리_없이_200을_반환한다() {
    FakePgWebhookHandler handler = new FakePgWebhookHandler();
    handler.idempotent = true;

    WebhookResult result = handler.handle(new WebhookRequest("body"));

    assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(handler.applyConditionalUpdateCalled).isFalse();
  }

  @Test
  void APPROVED면_onApproved만_호출된다() {
    UUID referenceId = UUID.randomUUID();
    FakePgWebhookHandler handler = new FakePgWebhookHandler();
    handler.updateResult = WebhookUpdateResult.approved(referenceId, "ok");

    WebhookResult result = handler.handle(new WebhookRequest("body"));

    assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(handler.onApprovedCalled).isTrue();
    assertThat(handler.onRejectedCalled).isFalse();
  }

  @Test
  void REJECTED면_onRejected만_호출된다() {
    UUID referenceId = UUID.randomUUID();
    FakePgWebhookHandler handler = new FakePgWebhookHandler();
    handler.updateResult = WebhookUpdateResult.rejected(referenceId, "rejected");

    WebhookResult result = handler.handle(new WebhookRequest("body"));

    assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
    assertThat(handler.onRejectedCalled).isTrue();
    assertThat(handler.onApprovedCalled).isFalse();
  }

  // §4.1 결함 재현 지점 — LOST_RACE/NO_MATCH/UNVERIFIABLE은 onApproved/onRejected 둘 다 호출하지 않는다.
  @Test
  void LOST_RACE_NO_MATCH_UNVERIFIABLE은_어떤_부수효과_콜백도_호출하지_않는다() {
    for (WebhookUpdateResult<String> result :
        new WebhookUpdateResult[] {
          WebhookUpdateResult.lostRace(UUID.randomUUID()),
          WebhookUpdateResult.noMatch(),
          WebhookUpdateResult.unverifiable()
        }) {
      FakePgWebhookHandler handler = new FakePgWebhookHandler();
      handler.updateResult = result;

      WebhookResult webhookResult = handler.handle(new WebhookRequest("body"));

      assertThat(webhookResult.getStatus()).isEqualTo(HttpStatus.OK);
      assertThat(handler.onApprovedCalled).as(result.outcome().name()).isFalse();
      assertThat(handler.onRejectedCalled).as(result.outcome().name()).isFalse();
    }
  }

  private static class FakePgWebhookHandler extends AbstractPgWebhookHandler<String, String> {

    private String parsedPayload = "payload";
    private boolean idempotent = false;
    private WebhookUpdateResult<String> updateResult =
        WebhookUpdateResult.approved(UUID.randomUUID(), "ok");
    private boolean checkIdempotencyCalled = false;
    private boolean applyConditionalUpdateCalled = false;
    private boolean onApprovedCalled = false;
    private boolean onRejectedCalled = false;

    @Override
    protected String parse(WebhookRequest request) {
      return parsedPayload;
    }

    @Override
    protected boolean checkIdempotency(String payload) {
      checkIdempotencyCalled = true;
      return idempotent;
    }

    @Override
    protected WebhookUpdateResult<String> applyConditionalUpdate(String payload) {
      applyConditionalUpdateCalled = true;
      return updateResult;
    }

    @Override
    protected void onApproved(String entity) {
      onApprovedCalled = true;
    }

    @Override
    protected void onRejected(String entity) {
      onRejectedCalled = true;
    }
  }
}
