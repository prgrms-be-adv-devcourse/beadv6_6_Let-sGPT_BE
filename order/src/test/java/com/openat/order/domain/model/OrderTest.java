package com.openat.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("주문 도메인")
class OrderTest {

    @Test
    @DisplayName("주문을 생성하면 결제 대기 상태와 총 금액을 기록한다")
    void create_whenCreated_hasPaymentPendingStatusAndTotalPrice() {
        // given
        Instant now = Instant.parse("2026-06-26T00:00:00Z");

        // when
        Order order = createOrder(now);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getTotalPrice()).isEqualTo(20_000L);
        assertThat(order.getPaymentExpiresAt()).isEqualTo(now.plusSeconds(10 * 60));
    }

    @Test
    @DisplayName("재고 부족이면 실패 상태와 실패 사유를 기록한다")
    void fail_whenSoldOut_recordsFailReason() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        Instant failedAt = Instant.parse("2026-06-26T00:01:00Z");

        // when
        boolean changed = order.fail(OrderFailCode.SOLD_OUT, "재고 부족", failedAt);

        // then
        assertThat(changed).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getFailCode()).isEqualTo(OrderFailCode.SOLD_OUT);
        assertThat(order.getFailMessage()).isEqualTo("재고 부족");
        assertThat(order.getCancelledAt()).isEqualTo(failedAt);
    }

    private Order createOrder(Instant now) {
        return Order.create()
                .orderNumber("ORD-20260626-0001")
                .memberId(UUID.randomUUID())
                .dropId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .quantity(2)
                .unitPrice(10_000L)
                .idempotencyKey("idem-001")
                .now(now)
                .build();
    }
}
