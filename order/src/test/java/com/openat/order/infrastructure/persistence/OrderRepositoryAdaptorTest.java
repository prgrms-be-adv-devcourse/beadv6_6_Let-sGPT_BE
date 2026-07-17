package com.openat.order.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.model.PurchaseSignal;
import com.openat.order.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(OrderRepositoryAdaptor.class)
@TestPropertySource(
        properties = {
            "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
            "spring.sql.init.mode=never"
        })
@DisplayName("주문 영속성 - 구매 신호 집계")
class OrderRepositoryAdaptorTest {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private OrderRepository orderRepository;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("같은 상품의 완료 주문 여러 건을 주문횟수·총수량·최근주문일로 합산한다")
    void findPurchaseSignals_aggregatesRepeatedPurchasesPerProduct() {
        UUID memberId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        persistOrder(memberId, productA, 2, OrderStatus.COMPLETED, at("2026-07-01"));
        persistOrder(memberId, productA, 1, OrderStatus.COMPLETED, at("2026-07-10"));
        persistOrder(memberId, productB, 1, OrderStatus.COMPLETED, at("2026-07-03"));

        List<PurchaseSignal> signals = orderRepository.findPurchaseSignals(
                memberId, OrderStatus.COMPLETED, PageRequest.of(0, 20));

        assertThat(signals)
                .extracting(
                        PurchaseSignal::productId,
                        PurchaseSignal::orderCount,
                        PurchaseSignal::totalQuantity,
                        PurchaseSignal::lastOrderedAt)
                .containsExactly(
                        tuple(productA, 2L, 3L, at("2026-07-10")),
                        tuple(productB, 1L, 1L, at("2026-07-03")));
    }

    @Test
    @DisplayName("완료 상태가 아닌 주문(환불 등)은 집계에서 제외한다")
    void findPurchaseSignals_excludesNonCompletedStatuses() {
        UUID memberId = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        persistOrder(memberId, product, 1, OrderStatus.COMPLETED, at("2026-07-01"));
        persistOrder(memberId, product, 3, OrderStatus.REFUNDED, at("2026-07-05"));
        persistOrder(memberId, product, 2, OrderStatus.REFUND_PENDING, at("2026-07-06"));
        persistOrder(memberId, product, 2, OrderStatus.REFUND_FAILED, at("2026-07-07"));
        persistOrder(memberId, product, 4, OrderStatus.PAYMENT_PENDING, at("2026-07-08"));

        List<PurchaseSignal> signals = orderRepository.findPurchaseSignals(
                memberId, OrderStatus.COMPLETED, PageRequest.of(0, 20));

        assertThat(signals)
                .containsExactly(new PurchaseSignal(product, 1L, 1L, at("2026-07-01")));
    }

    @Test
    @DisplayName("다른 회원의 완료 주문은 집계에 포함하지 않는다")
    void findPurchaseSignals_excludesOtherMembers() {
        UUID memberId = UUID.randomUUID();
        UUID otherMemberId = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        persistOrder(memberId, product, 1, OrderStatus.COMPLETED, at("2026-07-01"));
        persistOrder(otherMemberId, product, 5, OrderStatus.COMPLETED, at("2026-07-02"));

        List<PurchaseSignal> signals = orderRepository.findPurchaseSignals(
                memberId, OrderStatus.COMPLETED, PageRequest.of(0, 20));

        assertThat(signals)
                .containsExactly(new PurchaseSignal(product, 1L, 1L, at("2026-07-01")));
    }

    @Test
    @DisplayName("최근 주문일 내림차순으로 정렬하고 limit 개수로 자른다")
    void findPurchaseSignals_ordersByRecencyAndAppliesLimit() {
        UUID memberId = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        persistOrder(memberId, oldest, 1, OrderStatus.COMPLETED, at("2026-07-01"));
        persistOrder(memberId, middle, 1, OrderStatus.COMPLETED, at("2026-07-05"));
        persistOrder(memberId, newest, 1, OrderStatus.COMPLETED, at("2026-07-09"));

        List<PurchaseSignal> signals = orderRepository.findPurchaseSignals(
                memberId, OrderStatus.COMPLETED, PageRequest.of(0, 2));

        assertThat(signals)
                .extracting(PurchaseSignal::productId)
                .containsExactly(newest, middle);
    }

    @Test
    @DisplayName("완료 주문이 없는 회원은 빈 목록을 반환한다")
    void findPurchaseSignals_noCompletedOrders_returnsEmpty() {
        List<PurchaseSignal> signals = orderRepository.findPurchaseSignals(
                UUID.randomUUID(), OrderStatus.COMPLETED, PageRequest.of(0, 20));

        assertThat(signals).isEmpty();
    }

    private static Instant at(String date) {
        return Instant.parse(date + "T10:00:00Z");
    }

    private void persistOrder(
            UUID memberId, UUID productId, int quantity, OrderStatus status, Instant createdAt) {
        Order order = Order.create()
                .orderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 20))
                .memberId(memberId)
                .dropId(UUID.randomUUID())
                .productId(productId)
                .sellerId(UUID.randomUUID())
                .productName("테스트 상품")
                .quantity(quantity)
                .unitPrice(10_000L)
                .idempotencyKey(UUID.randomUUID().toString())
                .now(createdAt)
                .build();
        entityManager.persist(order);
        entityManager.flush();
        // createdAt: @CreationTimestamp가 강제 지정 → 저장 후 SQL로 재설정
        entityManager.createNativeQuery(
                        "UPDATE orders.orders SET created_at = :createdAt, status = :status WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("status", status.name())
                .setParameter("id", order.getId())
                .executeUpdate();
        entityManager.clear();
    }
}
