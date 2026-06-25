package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderInfo;
import com.openat.order.application.dto.OrderValidationInfo;
import com.openat.order.application.usecase.OrderQueryUseCase;
import com.openat.order.domain.error.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueryService implements OrderQueryUseCase {

    private final OrderRepository orderRepository;

    @Override
    public OrderInfo get(UUID memberId, UUID orderId) {
        Order order = find(orderId);
        if (!order.isOwnedBy(memberId)) {
            throw new BusinessException(OrderErrorCode.ORDER_FORBIDDEN);
        }
        return OrderInfo.from(order);
    }

    @Override
    public Page<OrderInfo> getMyOrders(UUID memberId, OrderStatus status, Pageable pageable) {
        return orderRepository.findByMemberId(memberId, status, pageable).map(OrderInfo::from);
    }

    @Override
    public OrderValidationInfo validateForPayment(UUID orderId) {
        Order order = find(orderId);
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
        }
        return OrderValidationInfo.from(order);
    }

    private Order find(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }
}
