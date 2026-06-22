package com.openat.payment.infrastructure.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

// 프레임워크(Spring) 의존 없이 템플릿 골격(서명검증→멱등→조건부UPDATE→분기)만 검증하는 순수 단위테스트(A13 목표).
class AbstractPgWebhookHandlerTest {

    private final TossSignatureVerifier signatureVerifier = mock(TossSignatureVerifier.class);

    @Test
    void 서명_검증에_실패하면_401을_반환하고_이후_단계는_호출되지_않는다() {
        when(signatureVerifier.verify("body", "bad-signature")).thenReturn(false);
        FakePgWebhookHandler handler = new FakePgWebhookHandler(signatureVerifier, List.of());

        WebhookResult result = handler.handle(new WebhookRequest("body", "bad-signature"));

        assertThat(result.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.applyConditionalUpdateCalled).isFalse();
    }

    @Test
    void 이미_처리된_이벤트는_재처리_없이_200을_반환한다() {
        when(signatureVerifier.verify("body", "good-signature")).thenReturn(true);
        FakePgWebhookHandler handler = new FakePgWebhookHandler(signatureVerifier, List.of());
        handler.idempotent = true;

        WebhookResult result = handler.handle(new WebhookRequest("body", "good-signature"));

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(handler.applyConditionalUpdateCalled).isFalse();
    }

    @Test
    void 조건부_UPDATE가_성공하면_onSuccess가_호출되고_리스너에_성공으로_통지된다() {
        when(signatureVerifier.verify("body", "good-signature")).thenReturn(true);
        UUID referenceId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        FakePgWebhookHandler handler = new FakePgWebhookHandler(signatureVerifier, List.of(listener));
        handler.updateResult = UpdateResult.success(referenceId, "ok");

        WebhookResult result = handler.handle(new WebhookRequest("body", "good-signature"));

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(handler.onSuccessCalled).isTrue();
        assertThat(handler.onFailureCalled).isFalse();
        assertThat(listener.outcomes).hasSize(1);
        assertThat(listener.outcomes.get(0).isSuccess()).isTrue();
        assertThat(listener.outcomes.get(0).getReferenceId()).isEqualTo(referenceId);
        assertThat(listener.outcomes.get(0).getHandlerType()).isEqualTo("FAKE");
    }

    @Test
    void 조건부_UPDATE가_실패하면_onFailure가_호출되고_리스너에_실패로_통지된다() {
        when(signatureVerifier.verify("body", "good-signature")).thenReturn(true);
        UUID referenceId = UUID.randomUUID();
        RecordingListener listener = new RecordingListener();
        FakePgWebhookHandler handler = new FakePgWebhookHandler(signatureVerifier, List.of(listener));
        handler.updateResult = UpdateResult.failure(referenceId, "race-lost");

        WebhookResult result = handler.handle(new WebhookRequest("body", "good-signature"));

        assertThat(result.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(handler.onFailureCalled).isTrue();
        assertThat(handler.onSuccessCalled).isFalse();
        assertThat(listener.outcomes.get(0).isSuccess()).isFalse();
    }

    @Test
    void 서명검증은_주입된_TossSignatureVerifier에_그대로_위임한다() {
        FakePgWebhookHandler handler = new FakePgWebhookHandler(signatureVerifier, List.of());

        handler.handle(new WebhookRequest("body", "sig"));

        verify(signatureVerifier).verify("body", "sig");
        verify(signatureVerifier, never()).verify("other", "sig");
    }

    private static class RecordingListener implements WebhookOutcomeListener {
        private final List<WebhookOutcome> outcomes = new ArrayList<>();

        @Override
        public void onOutcome(WebhookOutcome outcome) {
            outcomes.add(outcome);
        }
    }

    private static class FakePgWebhookHandler extends AbstractPgWebhookHandler<String> {

        private boolean idempotent = false;
        private UpdateResult<String> updateResult = UpdateResult.success(UUID.randomUUID(), "ok");
        private boolean applyConditionalUpdateCalled = false;
        private boolean onSuccessCalled = false;
        private boolean onFailureCalled = false;

        FakePgWebhookHandler(TossSignatureVerifier signatureVerifier, List<WebhookOutcomeListener> listeners) {
            super(signatureVerifier, listeners);
        }

        @Override
        protected boolean checkIdempotency(WebhookRequest request) {
            return idempotent;
        }

        @Override
        protected UpdateResult<String> applyConditionalUpdate(WebhookRequest request) {
            applyConditionalUpdateCalled = true;
            return updateResult;
        }

        @Override
        protected void onSuccess(UpdateResult<String> result) {
            onSuccessCalled = true;
        }

        @Override
        protected void onFailure(UpdateResult<String> result) {
            onFailureCalled = true;
        }

        @Override
        protected String handlerType() {
            return "FAKE";
        }
    }
}
