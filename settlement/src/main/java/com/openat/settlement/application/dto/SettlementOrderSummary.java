package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementOrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementOrderSummary(
        UUID id,
        UUID sellerSettlementId,
        UUID paymentId,
        UUID orderId,
        UUID sellerId,
        UUID buyerId,
        UUID productId,
        String settlementMonth,
        long orderAmount,
        long paidAmount,
        long feeAmount,
        long refundAmount,
        long netSettlementAmount,
        SettlementOrderStatus status,
        LocalDateTime paidAt
) {

    public static SettlementOrderSummary from(SettlementOrder order) {
        return new SettlementOrderSummary(
                order.getId(),
                order.getSellerSettlementId(),
                order.getPaymentId(),
                order.getOrderId(),
                order.getSellerId(),
                order.getBuyerId(),
                order.getProductId(),
                order.getSettlementMonth(),
                order.getOrderAmount(),
                order.getPaidAmount(),
                order.getFeeAmount(),
                order.getRefundAmount(),
                order.getNetSettlementAmount(),
                order.getSettlementStatus(),
                order.getPaidAt()
        );
    }
}
