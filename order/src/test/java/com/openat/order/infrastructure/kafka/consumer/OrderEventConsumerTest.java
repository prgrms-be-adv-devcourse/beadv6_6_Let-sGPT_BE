package com.openat.order.infrastructure.kafka.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.service.OrderEventService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private OrderEventService orderEventService;

    private OrderEventConsumer orderEventConsumer;

    @BeforeEach
    void setUp() {
        orderEventConsumer = new OrderEventConsumer(new ObjectMapper(), orderEventService);
    }

    @Test
    @DisplayName("결제 성공 이벤트 payload를 application command로 변환해 위임한다")
    void onPaymentCompleted_delegatesCommand() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = """
                {"orderId":"%s","version":"v1","paymentId":"%s","amount":10000}
                """.formatted(orderId, paymentId);

        // when
        orderEventConsumer.onPaymentCompleted(record("payment.complete.events", payload));

        // then
        verify(orderEventService).handlePaymentCompleted(
                new PaymentCompletedCommand(orderId, "v1", paymentId, 10_000L)
        );
    }

    @Test
    @DisplayName("환불 실패 이벤트 payload를 application command로 변환해 위임한다")
    void onRefundFailed_delegatesCommand() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        String payload = """
                {"orderId":"%s","version":"v1","paymentId":"%s","refundId":"%s","reason":"PG_REFUND_FAILED"}
                """.formatted(orderId, paymentId, refundId);

        // when
        orderEventConsumer.onRefundFailed(record("refund.failed.events", payload));

        // then
        verify(orderEventService).handleRefundFailed(
                new RefundFailedCommand(orderId, "v1", paymentId, refundId, "PG_REFUND_FAILED")
        );
    }

    @Test
    @DisplayName("이벤트 payload 파싱이 실패하면 RuntimeException을 던진다")
    void onPaymentCompleted_whenPayloadInvalid_throwRuntimeException() {
        // when & then
        assertThrows(RuntimeException.class,
                () -> orderEventConsumer.onPaymentCompleted(record("payment.complete.events", "invalid-json")));
        verify(orderEventService, org.mockito.Mockito.never()).handlePaymentCompleted(any());
    }

    private ConsumerRecord<String, String> record(String topic, String payload) {
        return new ConsumerRecord<>(topic, 0, 0L, null, payload);
    }
}
