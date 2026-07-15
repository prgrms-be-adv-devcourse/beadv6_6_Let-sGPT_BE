package com.openat.order.application.service;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderSagaStep;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 생성 사가의 단계(OrderSagaState.currentStep)를 기록한다.
 * 트랜잭션 경계는 설계문서(`주문_파이널_설계.md` §2.1)를 따른다:
 * - ORDER_CREATED / COMPLETED / COMPENSATING: 호출부의 상태 전이 트랜잭션에 참여(같은 커밋).
 * - STOCK_DECREASED / COMPENSATION_COMPLETED: 외부 호출(product 응답) 직후 별도 커밋(REQUIRES_NEW).
 * 사가 row가 없는 주문(파이널 이전 생성 레거시)은 갱신을 스킵하고 예외 없이 정상 진행한다.
 */
@Component
@RequiredArgsConstructor
public class OrderSagaRecorder {

    private final OrderSagaStateRepository orderSagaStateRepository;

    @Transactional
    public void recordOrderCreated(Order order) {
        String sagaId = order.getId().toString();
        order.assignSagaId(sagaId);
        orderSagaStateRepository.save(
                OrderSagaState.create()
                        .orderId(order.getId())
                        .sagaId(sagaId)
                        .build()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStockDecreased(UUID orderId) {
        advance(orderId, OrderSagaStep.STOCK_DECREASED);
    }

    @Transactional
    public void recordCompleted(UUID orderId) {
        advance(orderId, OrderSagaStep.COMPLETED);
    }

    @Transactional
    public void recordCompensating(UUID orderId) {
        orderSagaStateRepository.findByOrderId(orderId)
                .ifPresent(OrderSagaState::enterCompensating);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompensationCompleted(UUID orderId) {
        advance(orderId, OrderSagaStep.COMPENSATION_COMPLETED);
    }

    private void advance(UUID orderId, OrderSagaStep step) {
        orderSagaStateRepository.findByOrderId(orderId)
                .ifPresent(sagaState -> sagaState.advanceTo(step));
    }
}
