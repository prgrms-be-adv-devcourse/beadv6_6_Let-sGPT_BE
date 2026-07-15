package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.openat.common.exception.BusinessException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.InboxEvent;
import com.openat.order.domain.model.InboxEventStatus;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.InboxEventRepository;
import com.openat.order.domain.repository.OrderRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

class InboxEventFailureRecorderTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(InboxEventFailureRecorder.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest(name = "{0}: financialImpact={1}")
    @CsvSource({
            "payment.completed, true",
            "refund.completed, true",
            "refund.failed, true",
            "payment.failed, false"
    })
    @DisplayName("비즈니스 거부의 financialImpact 태그는 확정된 이벤트 타입에만 기록한다")
    void record_businessRejection_logsFinancialImpactOnlyForConfiguredEventTypes(
            String eventType,
            boolean expectedFinancialImpact
    ) {
        UUID orderId = UUID.randomUUID();
        String eventId = eventType + ":event:" + orderId;
        InboxEvent inboxEvent = InboxEvent.receive()
                .eventId(eventId)
                .eventType(eventType)
                .payload("{}")
                .build();
        InboxEventRepository inboxEventRepository = mock(InboxEventRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        when(inboxEventRepository.findByEventId(eventId)).thenReturn(Optional.of(inboxEvent));
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(OrderStatus.PAYMENT_PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        OrderHistoryRecorder orderHistoryRecorder = mock(OrderHistoryRecorder.class);
        InboxEventFailureRecorder recorder = new InboxEventFailureRecorder(
                inboxEventRepository,
                orderRepository,
                orderHistoryRecorder);

        recorder.record(
                eventId,
                eventType,
                "{}",
                orderId,
                new BusinessException(OrderErrorCode.INVALID_STATUS),
                true);

        boolean financialImpactLogged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("financialImpact=true"));
        assertThat(financialImpactLogged).isEqualTo(expectedFinancialImpact);
        assertThat(inboxEvent.getStatus()).isEqualTo(InboxEventStatus.FAILED);
        verify(orderHistoryRecorder).record(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("EVENT_PROCESSING_REJECTED"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(eventId));
    }
}
