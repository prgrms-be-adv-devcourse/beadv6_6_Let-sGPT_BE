package com.openat.payment.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openat.payment.application.dto.DummyPaymentCompletedEvent;
import com.openat.payment.application.dto.DummyPaymentRefundedEvent;
import com.openat.payment.infrastructure.devtools.DummySettlementEventPublisher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

// 순수 Mockito 단위테스트 — research.md §20, plan.md S4. 더미 발행 API가 publisher에 넘기는 eventType 값을 검증.
class DummySettlementEventControllerTest {

    private final DummySettlementEventPublisher publisher = mock(DummySettlementEventPublisher.class);

    private DummySettlementEventController controller;

    @BeforeEach
    void setUp() {
        controller = new DummySettlementEventController(publisher);
    }

    @Test
    void 결제완료_더미발행_API는_eventType을_PaymentSettlementCompleted로_채워_count만큼_발행한다() {
        UUID sellerId = UUID.randomUUID();

        ResponseEntity<Integer> response = controller.publishPaymentCompleted(2, sellerId);

        assertThat(response.getBody()).isEqualTo(2);
        ArgumentCaptor<DummyPaymentCompletedEvent> captor = ArgumentCaptor.forClass(DummyPaymentCompletedEvent.class);
        verify(publisher, times(2)).publishPaymentCompleted(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(event -> {
                    assertThat(event.eventType()).isEqualTo("PaymentSettlementCompleted");
                    assertThat(event.sellerId()).isEqualTo(sellerId);
                });
    }

    @Test
    void 환불완료_더미발행_API는_eventType을_RefundSettlementCompleted로_채워_count만큼_발행한다() {
        UUID sellerId = UUID.randomUUID();

        ResponseEntity<Integer> response = controller.publishPaymentRefunded(3, sellerId);

        assertThat(response.getBody()).isEqualTo(3);
        ArgumentCaptor<DummyPaymentRefundedEvent> captor = ArgumentCaptor.forClass(DummyPaymentRefundedEvent.class);
        verify(publisher, times(3)).publishPaymentRefunded(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(event -> {
                    assertThat(event.eventType()).isEqualTo("RefundSettlementCompleted");
                    assertThat(event.sellerId()).isEqualTo(sellerId);
                });
    }

    @Test
    void sellerId를_주지_않으면_매_호출마다_랜덤_sellerId가_채워진다() {
        controller.publishPaymentCompleted(2, null);

        ArgumentCaptor<DummyPaymentCompletedEvent> captor = ArgumentCaptor.forClass(DummyPaymentCompletedEvent.class);
        verify(publisher, times(2)).publishPaymentCompleted(captor.capture());
        assertThat(captor.getAllValues().get(0).sellerId()).isNotEqualTo(captor.getAllValues().get(1).sellerId());
    }
}
