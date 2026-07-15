package com.openat.order.infrastructure.kafka.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.service.OrderEventService;
import com.openat.order.application.service.InboxEventFailureRecorder;
import com.openat.order.application.service.InboxEventProcessor;
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

    @Mock
    private InboxEventProcessor inboxEventProcessor;

    @Mock
    private InboxEventFailureRecorder inboxEventFailureRecorder;

    private OrderEventConsumer orderEventConsumer;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return null;
        }).when(inboxEventProcessor).process(any(), any(), any(), any());
        orderEventConsumer = new OrderEventConsumer(
                new ObjectMapper(), orderEventService, inboxEventProcessor, inboxEventFailureRecorder);
    }

    @Test
    @DisplayName("결제 성공 이벤트 payload를 application command로 변환해 위임한다")
    void onPaymentCompleted_delegatesCommand() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = """
                {"orderId":"%s","paymentId":"%s","amount":10000}
                """.formatted(orderId, paymentId);

        // when
        orderEventConsumer.onPaymentCompleted(record("payment.completed.events", payload));

        // then
        verify(orderEventService).handlePaymentCompleted(
                new PaymentCompletedCommand(orderId, paymentId, 10_000L)
        );
        verify(inboxEventProcessor).process(
                org.mockito.ArgumentMatchers.eq("payment.completed:" + paymentId + ":" + orderId),
                org.mockito.ArgumentMatchers.eq("payment.completed"),
                org.mockito.ArgumentMatchers.eq(payload),
                any());
    }

    @Test
    @DisplayName("결제 실패 이벤트 payload를 application command로 변환해 위임한다")
    void onPaymentFailed_delegatesCommand() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = """
                {"orderId":"%s","paymentId":"%s","reason":"PG_TIMEOUT"}
                """.formatted(orderId, paymentId);

        // when
        orderEventConsumer.onPaymentFailed(record("payment.failed.events", payload));

        // then
        verify(orderEventService).handlePaymentFailed(
                new PaymentFailedCommand(orderId, paymentId, "PG_TIMEOUT")
        );
    }

    @Test
    @DisplayName("환불 완료 이벤트 payload를 application command로 변환해 위임한다")
    void onRefundCompleted_delegatesCommand() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        String payload = """
                {"orderId":"%s","paymentId":"%s","refundId":"%s","amount":10000}
                """.formatted(orderId, paymentId, refundId);

        // when
        orderEventConsumer.onRefundCompleted(record("refund.completed.events", payload));

        // then
        verify(orderEventService).handleRefundCompleted(
                new RefundCompletedCommand(orderId, paymentId, 10_000L, refundId)
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
                {"orderId":"%s","paymentId":"%s","refundId":"%s","reason":"PG_REFUND_FAILED"}
                """.formatted(orderId, paymentId, refundId);

        // when
        orderEventConsumer.onRefundFailed(record("refund.failed.events", payload));

        // then
        verify(orderEventService).handleRefundFailed(
                new RefundFailedCommand(orderId, paymentId, refundId, "PG_REFUND_FAILED")
        );
    }

    @Test
    @DisplayName("이벤트 payload 파싱이 실패하면 RuntimeException을 던진다")
    void onPaymentCompleted_whenPayloadInvalid_throwRuntimeException() {
        // when & then
        assertThrows(RuntimeException.class,
                () -> orderEventConsumer.onPaymentCompleted(record("payment.completed.events", "invalid-json")));
        verify(orderEventService, org.mockito.Mockito.never()).handlePaymentCompleted(any());
        verify(inboxEventFailureRecorder).record(
                org.mockito.ArgumentMatchers.eq("kafka:payment.completed.events:0:0"),
                org.mockito.ArgumentMatchers.eq("payment.completed"),
                org.mockito.ArgumentMatchers.eq("invalid-json"),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                org.mockito.ArgumentMatchers.eq(false));
    }

    private ConsumerRecord<String, String> record(String topic, String payload) {
        return new ConsumerRecord<>(topic, 0, 0L, null, payload);
    }
}
