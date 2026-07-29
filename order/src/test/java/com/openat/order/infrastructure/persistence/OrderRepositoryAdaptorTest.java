package com.openat.order.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.openat.order.application.dto.OrderSummaryInfo;
import com.openat.order.application.service.OrderCancellationService;
import com.openat.order.application.service.OrderCompensationService;
import com.openat.order.application.service.OrderCreationService;
import com.openat.order.application.service.OrderService;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.model.PurchaseSignal;
import com.openat.order.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({OrderRepositoryAdaptor.class, OrderService.class})
@TestPropertySource(properties = "spring.sql.init.mode=never")
@DisplayName("주문 영속성 - 구매 신호 집계·목록 페이징")
class OrderRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderService orderService;
  @PersistenceContext private EntityManager entityManager;

  @MockitoBean private OrderCreationService orderCreationService;
  @MockitoBean private OrderCancellationService orderCancellationService;
  @MockitoBean private OrderCompensationService orderCompensationService;

  @Test
  @DisplayName("같은 상품의 완료 주문 여러 건을 주문횟수·총수량·최근주문일로 합산한다")
  void findPurchaseSignals_aggregatesRepeatedPurchasesPerProduct() {
    UUID memberId = UUID.randomUUID();
    UUID productA = UUID.randomUUID();
    UUID productB = UUID.randomUUID();
    persistOrder(memberId, productA, 2, OrderStatus.COMPLETED, at("2026-07-01"));
    persistOrder(memberId, productA, 1, OrderStatus.COMPLETED, at("2026-07-10"));
    persistOrder(memberId, productB, 1, OrderStatus.COMPLETED, at("2026-07-03"));

    List<PurchaseSignal> signals =
        orderRepository.findPurchaseSignals(memberId, OrderStatus.COMPLETED, PageRequest.of(0, 20));

    assertThat(signals)
        .extracting(
            PurchaseSignal::productId,
            PurchaseSignal::orderCount,
            PurchaseSignal::totalQuantity,
            PurchaseSignal::lastOrderedAt)
        .containsExactly(
            tuple(productA, 2L, 3L, at("2026-07-10")), tuple(productB, 1L, 1L, at("2026-07-03")));
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

    List<PurchaseSignal> signals =
        orderRepository.findPurchaseSignals(memberId, OrderStatus.COMPLETED, PageRequest.of(0, 20));

    assertThat(signals).containsExactly(new PurchaseSignal(product, 1L, 1L, at("2026-07-01")));
  }

  @Test
  @DisplayName("다른 회원의 완료 주문은 집계에 포함하지 않는다")
  void findPurchaseSignals_excludesOtherMembers() {
    UUID memberId = UUID.randomUUID();
    UUID otherMemberId = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    persistOrder(memberId, product, 1, OrderStatus.COMPLETED, at("2026-07-01"));
    persistOrder(otherMemberId, product, 5, OrderStatus.COMPLETED, at("2026-07-02"));

    List<PurchaseSignal> signals =
        orderRepository.findPurchaseSignals(memberId, OrderStatus.COMPLETED, PageRequest.of(0, 20));

    assertThat(signals).containsExactly(new PurchaseSignal(product, 1L, 1L, at("2026-07-01")));
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

    List<PurchaseSignal> signals =
        orderRepository.findPurchaseSignals(memberId, OrderStatus.COMPLETED, PageRequest.of(0, 2));

    assertThat(signals).extracting(PurchaseSignal::productId).containsExactly(newest, middle);
  }

  @Test
  @DisplayName("완료 주문이 없는 회원은 빈 목록을 반환한다")
  void findPurchaseSignals_noCompletedOrders_returnsEmpty() {
    List<PurchaseSignal> signals =
        orderRepository.findPurchaseSignals(
            UUID.randomUUID(), OrderStatus.COMPLETED, PageRequest.of(0, 20));

    assertThat(signals).isEmpty();
  }

  @Test
  @DisplayName("주문 목록은 생성 시각 내림차순으로 정렬한다")
  void getMyOrders_ordersByCreatedAtDesc() {
    UUID memberId = UUID.randomUUID();
    UUID oldest =
        persistOrder(memberId, UUID.randomUUID(), 1, OrderStatus.COMPLETED, at("2026-07-01"));
    UUID newest =
        persistOrder(memberId, UUID.randomUUID(), 1, OrderStatus.COMPLETED, at("2026-07-09"));
    UUID middle =
        persistOrder(memberId, UUID.randomUUID(), 1, OrderStatus.COMPLETED, at("2026-07-05"));

    var page = orderService.getMyOrders(memberId, null, PageRequest.of(0, 10));

    assertThat(page.getContent())
        .extracting(OrderSummaryInfo::orderId)
        .containsExactly(newest, middle, oldest);
  }

  @Test
  @DisplayName("생성 시각이 같은 주문도 페이지를 넘길 때 중복·누락되지 않는다")
  void getMyOrders_pagesWithoutDuplicatesOnCreatedAtTie() {
    UUID memberId = UUID.randomUUID();
    Instant sameMoment = at("2026-07-20");
    List<UUID> persisted = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      persisted.add(
          persistOrder(memberId, UUID.randomUUID(), 1, OrderStatus.COMPLETED, sameMoment));
    }

    List<UUID> paged = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      paged.addAll(
          orderService.getMyOrders(memberId, null, PageRequest.of(page, 2)).getContent().stream()
              .map(OrderSummaryInfo::orderId)
              .toList());
    }

    assertThat(paged).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(persisted);
  }

  private static Instant at(String date) {
    return Instant.parse(date + "T10:00:00Z");
  }

  private UUID persistOrder(
      UUID memberId, UUID productId, int quantity, OrderStatus status, Instant createdAt) {
    Order order =
        Order.create()
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
    // @CreationTimestamp가 createdAt을 덮어써서 SQL로 되돌린다 — 지우면 날짜 조건 검증이 무력화된다
    entityManager
        .createNativeQuery(
            "UPDATE orders.orders SET created_at = :createdAt, status = :status WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("status", status.name())
        .setParameter("id", order.getId())
        .executeUpdate();
    entityManager.clear();
    return order.getId();
  }
}
