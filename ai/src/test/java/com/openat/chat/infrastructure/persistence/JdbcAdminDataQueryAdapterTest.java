package com.openat.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.application.port.DataQueryCapabilityState;
import com.openat.chat.domain.query.AdminDataQueryPlan.OrderLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 소형 읽기 도구 JDBC 어댑터")
class JdbcAdminDataQueryAdapterTest {

  private static final Instant NOW = Instant.parse("2026-07-23T03:00:00Z");

  @Mock NamedParameterJdbcTemplate jdbcTemplate;
  @Mock DataQueryCapabilityState capabilityState;
  @Mock PlatformTransactionManager transactionManager;

  private JdbcAdminDataQueryAdapter adapter;

  @BeforeEach
  void setUp() {
    adapter =
        new JdbcAdminDataQueryAdapter(
            jdbcTemplate, capabilityState, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("개별 주문은 일반 조회 뷰가 아니라 exact SECURITY DEFINER 함수만 호출한다")
  void orderLookupSql_usesExactFunctionsOnly() {
    assertThat(adapter.orderDetailSql())
        .contains("ai_read.lookup_order_detail(:orderNumber)")
        .contains("unit_price", "total_price")
        .doesNotContain("v_order_details")
        .doesNotContain("SELECT *");
    assertThat(adapter.orderEventsSql())
        .contains("ai_read.lookup_order_process_events(:orderNumber)")
        .doesNotContain("v_order_process_events")
        .doesNotContain("SELECT *");
    assertThat(adapter.orderSagaSql())
        .contains("ai_read.lookup_order_current_saga(:orderNumber)")
        .doesNotContain("v_order_current_saga")
        .doesNotContain("SELECT *");
  }

  @Test
  @DisplayName("공개 주문번호와 조회 범위는 계획 생성 시점에 제한한다")
  void orderLookupPlan_rejectsUnsafeOrEmptyValues() {
    assertThatThrownBy(() -> new OrderLookup("ORD-20260723-ABC123' OR 1=1", true, true, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("주문번호");
    assertThatThrownBy(() -> new OrderLookup("ORD-20260723-ABC123", false, false, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("조회 범위");
  }

  @Test
  @DisplayName("고정 지표 조회는 3초 제한의 read-only repeatable-read 트랜잭션에서 실행한다")
  void countExpiredPaymentPendingOrders_usesBoundedReadOnlyTransaction() {
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    when(capabilityState.isAvailable()).thenReturn(true);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(transactionStatus);
    when(jdbcTemplate.queryForObject(
            any(String.class),
            any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
            org.mockito.ArgumentMatchers.<Class<Long>>any()))
        .thenReturn(3L);

    AdminDataQueryResult.Metric result = adapter.countExpiredPaymentPendingOrders();

    assertThat(result.value()).isEqualTo(3);
    assertThat(result.asOf()).isEqualTo(NOW);
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().isReadOnly()).isTrue();
    assertThat(definition.getValue().getTimeout()).isEqualTo(3);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    verify(transactionManager).commit(transactionStatus);
  }

  @Test
  @DisplayName("주문 스냅샷과 처리 이벤트는 안전한 값과 101개 후보 상한을 검증한다")
  void validateOrderLookupResults_rejectsUnsafeValuesAndCandidateOverflow() {
    AdminDataQueryResult.OrderSnapshot validSnapshot =
        new AdminDataQueryResult.OrderSnapshot(
            "ORD-20260723010203-ABC123",
            "여름 한정 상품",
            1,
            10_000,
            10_000,
            "COMPLETED",
            null,
            null,
            NOW.minusSeconds(60),
            NOW,
            NOW,
            NOW,
            null,
            null);
    List<AdminDataQueryResult.OrderProcessEvent> events = events(102);

    assertThatCode(() -> adapter.validateSnapshot(validSnapshot)).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                adapter.validateSnapshot(
                    new AdminDataQueryResult.OrderSnapshot(
                        validSnapshot.publicOrderNumber(),
                        "잘못된\n상품명",
                        validSnapshot.quantity(),
                        validSnapshot.unitPrice(),
                        validSnapshot.totalPrice(),
                        validSnapshot.status(),
                        validSnapshot.failCode(),
                        validSnapshot.paymentExpiresAt(),
                        validSnapshot.createdAt(),
                        validSnapshot.updatedAt(),
                        validSnapshot.paidAt(),
                        validSnapshot.completedAt(),
                        validSnapshot.cancelledAt(),
                        validSnapshot.refundedAt())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("스냅샷");
    assertThatThrownBy(() -> adapter.validateEvents(events))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("행 수");
    assertThatCode(() -> adapter.validateEvents(events.subList(0, 101))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("주문 이벤트는 99·100건을 그대로 반환하고 101건부터 잘림을 표시한다")
  void limitOrderEvents_detectsExactTruncationBoundary() {
    for (int count : List.of(99, 100, 101)) {
      JdbcAdminDataQueryAdapter.BoundedOrderEvents result = adapter.limitOrderEvents(events(count));

      assertThat(result.events()).hasSize(Math.min(count, 100));
      assertThat(result.truncated()).isEqualTo(count == 101);
    }
  }

  private List<AdminDataQueryResult.OrderProcessEvent> events(int count) {
    List<AdminDataQueryResult.OrderProcessEvent> events = new ArrayList<>();
    for (int index = 1; index <= count; index++) {
      events.add(
          new AdminDataQueryResult.OrderProcessEvent(
              index, NOW.plusSeconds(index), null, "COMPLETED", "PAYMENT_COMPLETED"));
    }
    return events;
  }
}
