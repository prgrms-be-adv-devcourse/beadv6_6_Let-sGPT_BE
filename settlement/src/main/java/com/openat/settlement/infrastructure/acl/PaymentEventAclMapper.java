package com.openat.settlement.infrastructure.acl;

import com.openat.settlement.application.dto.RecordPaymentCompletedCommand;
import com.openat.settlement.application.dto.RecordPaymentRefundedCommand;
import com.openat.settlement.infrastructure.kafka.event.PaymentCompletedEvent;
import com.openat.settlement.infrastructure.kafka.event.PaymentRefundedEvent;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventAclMapper {

    /*
     - 외부 이벤트 변경과 내부 정산 로직 변경을 분리하려고 만든것
     - 결제 이벤트가 바뀌면 주로 수정하는 곳
        - infrastructure.kafka.event.PaymentCompletedEvent
        - infrastructure.kafka.acl.PaymentEventAclMapper
     */
    public RecordPaymentCompletedCommand toCommand(PaymentCompletedEvent event) {
        return new RecordPaymentCompletedCommand(
                event.eventId(),
                event.paymentId(),
                event.orderId(),
                event.sellerId(),
                event.buyerId(),
                event.productId(),
                event.settlementMonth(),
                event.orderAmount(),
                event.paidAmount(),
                event.feeAmount(),
                event.paidAt()
        );
    }

    public RecordPaymentRefundedCommand toCommand(PaymentRefundedEvent event) {
        return new RecordPaymentRefundedCommand(
                event.eventId(),
                event.refundId(),
                event.paymentId(),
                event.orderId(),
                event.sellerId(),
                event.buyerId(),
                event.refundAmount(),
                event.refundReason(),
                event.settlementMonth(),
                event.refundedAt()
        );
    }
}
