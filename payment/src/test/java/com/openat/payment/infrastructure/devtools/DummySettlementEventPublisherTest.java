package com.openat.payment.infrastructure.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openat.payment.application.dto.DummyPaymentCompletedEvent;
import com.openat.payment.application.dto.DummyPaymentRefundedEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

// 순수 Mockito 단위테스트 — research.md §20, plan.md S3. 결제/환불 더미 이벤트가 같은 토픽으로 발행되는지 검증.
class DummySettlementEventPublisherTest {

    private static final String TOPIC = "payment.settlement.events";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private DummySettlementEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new DummySettlementEventPublisher(kafkaTemplate, objectMapper);
    }

    @Test
    void 결제완료_더미이벤트는_payment_settlement_events_토픽으로_PaymentSettlementCompleted_타입으로_발행된다() throws Exception {
        UUID paymentId = UUID.randomUUID();
        DummyPaymentCompletedEvent event = new DummyPaymentCompletedEvent(UUID.randomUUID().toString(),
                "PaymentSettlementCompleted", LocalDateTime.now(), paymentId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10000L, 10000L, LocalDateTime.now());

        publisher.publishPaymentCompleted(event);

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(paymentId.toString()), payloadCaptor.capture());

        DummyPaymentCompletedEvent published =
                objectMapper.readValue(payloadCaptor.getValue(), DummyPaymentCompletedEvent.class);
        assertThat(published.eventType()).isEqualTo("PaymentSettlementCompleted");
    }

    @Test
    void 환불완료_더미이벤트는_결제와_같은_payment_settlement_events_토픽으로_RefundSettlementCompleted_타입으로_발행된다() throws Exception {
        UUID paymentId = UUID.randomUUID();
        DummyPaymentRefundedEvent event = new DummyPaymentRefundedEvent(UUID.randomUUID().toString(),
                "RefundSettlementCompleted", LocalDateTime.now(), UUID.randomUUID(), paymentId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "단순변심", LocalDateTime.now());

        publisher.publishPaymentRefunded(event);

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(paymentId.toString()), payloadCaptor.capture());

        DummyPaymentRefundedEvent published =
                objectMapper.readValue(payloadCaptor.getValue(), DummyPaymentRefundedEvent.class);
        assertThat(published.eventType()).isEqualTo("RefundSettlementCompleted");
    }

    @Test
    void 결제완료와_환불완료_더미이벤트는_서로_다른_토픽이_아니라_같은_토픽으로_발행된다() {
        UUID paymentId = UUID.randomUUID();
        publisher.publishPaymentCompleted(new DummyPaymentCompletedEvent(UUID.randomUUID().toString(),
                "PaymentSettlementCompleted", LocalDateTime.now(), paymentId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10000L, 10000L, LocalDateTime.now()));
        publisher.publishPaymentRefunded(new DummyPaymentRefundedEvent(UUID.randomUUID().toString(),
                "RefundSettlementCompleted", LocalDateTime.now(), UUID.randomUUID(), paymentId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "단순변심", LocalDateTime.now()));

        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(eq(TOPIC), any(), any());
    }
}
