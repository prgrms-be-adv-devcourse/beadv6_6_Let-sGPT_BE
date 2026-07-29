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
    Instant now = Instant.parse("2026-06-26T00:00:00Z");

    Order order = createOrder(now);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    assertThat(order.getTotalPrice()).isEqualTo(20_000L);
    assertThat(order.getPaymentExpiresAt()).isEqualTo(now.plusSeconds(10 * 60));
  }

  @Test
  @DisplayName("재고 부족이면 실패 상태와 실패 사유를 기록한다")
  void fail_whenSoldOut_recordsFailReason() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    Instant failedAt = Instant.parse("2026-06-26T00:01:00Z");

    boolean changed = order.fail(OrderFailCode.SOLD_OUT, "재고 부족", failedAt);

    assertThat(changed).isTrue();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.SOLD_OUT);
    assertThat(order.getFailMessage()).isEqualTo("재고 부족");
    assertThat(order.getCancelledAt()).isEqualTo(failedAt);
  }

  @Test
  void should_allow_refund_when_order_cancelled() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.cancelPending(Instant.parse("2026-06-26T00:01:00Z"));

    boolean changed = order.refund(Instant.parse("2026-06-26T00:02:00Z"));

    assertThat(changed).isTrue();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
  }

  @Test
  @DisplayName("환불 접수 미확정 표식은 사용자에게 노출할 실패 사유에서 감춘다")
  void getUserVisibleFailCode_whenRefundRequestUnconfirmed_returnsNull() {
    Order order = refundRequestedOrder();
    order.recordFailure(OrderFailCode.REFUND_REQUEST_FAILED, "환불 요청 접수 미확인");

    assertThat(order.isRefundRequestUnconfirmed()).isTrue();
    assertThat(order.getUserVisibleFailCode()).isNull();
    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.REFUND_REQUEST_FAILED);
  }

  @Test
  @DisplayName("환불이 실패로 끝난 주문은 실패 사유를 그대로 노출한다")
  void getUserVisibleFailCode_whenRefundFailed_returnsFailCode() {
    Order order = refundRequestedOrder();
    order.failRefund("PG 환불 거절");

    assertThat(order.getUserVisibleFailCode()).isEqualTo(OrderFailCode.PG_ERROR);

    order.recordFailure(OrderFailCode.REFUND_REQUEST_FAILED, "재트리거 실패");

    assertThat(order.isRefundRequestUnconfirmed()).isFalse();
    assertThat(order.getUserVisibleFailCode()).isEqualTo(OrderFailCode.REFUND_REQUEST_FAILED);
  }

  private Order refundRequestedOrder() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:01:00Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:02:00Z"));
    return order;
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
