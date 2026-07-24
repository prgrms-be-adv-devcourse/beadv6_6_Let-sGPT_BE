package com.openat.chat.infrastructure.inference.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.port.AdminDataQueryPort;
import com.openat.chat.application.port.ChatEventSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
@DisplayName("Spring AI 내부 데이터 도구")
class AdminDataToolsTest {

  @Mock AdminDataQueryPort queryPort;

  private AdminDataTools tools;
  private RecordingSink sink;
  private ToolContext toolContext;

  @BeforeEach
  void setUp() {
    tools = new AdminDataTools(queryPort);
    sink = new RecordingSink();
    ChatCommand command =
        new ChatCommand(UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "오늘 주문을 상태별로 알려줘");
    toolContext =
        new ToolContext(
            Map.of(
                AdminToolExecutionContext.KEY,
                new AdminToolExecutionContext(command.requestId(), command.message(), sink)));
  }

  @Test
  @DisplayName("모델 주문번호가 질문 원문과 다르면 DB 조회를 실행하지 않는다")
  void lookupOrder_modelValueMismatch_blocksQuery() {
    given(queryPort.isAvailable()).willReturn(true);
    ChatCommand command =
        new ChatCommand(
            UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "ORD-REAL-0001 주문 사가를 알려줘");
    ToolContext secureContext =
        new ToolContext(
            Map.of(
                AdminToolExecutionContext.KEY,
                new AdminToolExecutionContext(
                    command.requestId(), command.message(), new RecordingSink())));

    AdminToolResult result = tools.lookupOrder("ORD-MODEL-9999", true, true, true, secureContext);

    assertThat(result.code()).isEqualTo("UNVERIFIED_ORDER_REFERENCE");
    verify(queryPort, never()).lookupOrder(any());
  }

  @Test
  @DisplayName("현재 상태를 요청하지 않아도 내부 존재 확인 결과가 없으면 주문 없음으로 답한다")
  void lookupOrder_withoutSnapshotStillChecksExistence() {
    given(queryPort.isAvailable()).willReturn(true);
    given(queryPort.lookupOrder(any()))
        .willReturn(
            new AdminDataQueryResult.OrderLookup(
                java.util.Optional.empty(),
                List.of(),
                java.util.Optional.empty(),
                false,
                Instant.parse("2026-07-24T00:00:00Z")));
    ChatCommand command =
        new ChatCommand(UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "ORD-NOT-FOUND 이벤트를 알려줘");
    ToolContext secureContext =
        new ToolContext(
            Map.of(
                AdminToolExecutionContext.KEY,
                new AdminToolExecutionContext(
                    command.requestId(), command.message(), new RecordingSink())));

    AdminToolResult result = tools.lookupOrder("ORD-NOT-FOUND", false, true, false, secureContext);

    assertThat(result.code()).isEqualTo("ORDER_NOT_FOUND");
    verify(queryPort).lookupOrder(any());
  }

  @Test
  @DisplayName("존재 확인용 스냅샷과 요청하지 않은 사가는 도구 결과에서 숨긴다")
  void lookupOrder_unrequestedSections_areNotExposed() {
    given(queryPort.isAvailable()).willReturn(true);
    Instant asOf = Instant.parse("2026-07-24T00:00:00Z");
    given(queryPort.lookupOrder(any()))
        .willReturn(
            new AdminDataQueryResult.OrderLookup(
                java.util.Optional.of(
                    new AdminDataQueryResult.OrderSnapshot(
                        "ORD-REAL-0001",
                        "테스트 상품",
                        1,
                        10_000,
                        10_000,
                        "PAYMENT_PENDING",
                        null,
                        asOf,
                        asOf,
                        asOf,
                        null,
                        null,
                        null,
                        null)),
                List.of(new AdminDataQueryResult.OrderProcessEvent(1, asOf, null, "CREATED", null)),
                java.util.Optional.of(
                    new AdminDataQueryResult.OrderSagaSnapshot("PAYMENT", null, asOf, asOf)),
                true,
                asOf));
    ChatCommand command =
        new ChatCommand(UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "ORD-REAL-0001 이벤트를 알려줘");
    ToolContext secureContext =
        new ToolContext(
            Map.of(
                AdminToolExecutionContext.KEY,
                new AdminToolExecutionContext(
                    command.requestId(), command.message(), new RecordingSink())));

    AdminToolResult result = tools.lookupOrder("ORD-REAL-0001", false, true, false, secureContext);

    assertThat(result.status()).isEqualTo(AdminToolResult.Status.SUCCESS);
    assertThat(result.data()).isInstanceOf(AdminDataTools.OrderLookupFacts.class);
    AdminDataTools.OrderLookupFacts facts = (AdminDataTools.OrderLookupFacts) result.data();
    assertThat(facts.snapshotStatus()).isEqualTo("NOT_REQUESTED");
    assertThat(facts.snapshot()).isNull();
    assertThat(facts.processEvents()).hasSize(1);
    assertThat(facts.currentSagaStatus()).isEqualTo("NOT_REQUESTED");
    assertThat(facts.currentSaga()).isNull();
    assertThat(facts.processEventsTruncated()).isTrue();
  }

  @Test
  @DisplayName("개별 주문 스냅샷에는 상품명과 주문 당시 가격을 반환한다")
  void lookupOrder_requestedSnapshot_exposesSafeCommerceFields() {
    given(queryPort.isAvailable()).willReturn(true);
    Instant asOf = Instant.parse("2026-07-24T00:00:00Z");
    given(queryPort.lookupOrder(any()))
        .willReturn(
            new AdminDataQueryResult.OrderLookup(
                java.util.Optional.of(
                    new AdminDataQueryResult.OrderSnapshot(
                        "ORD-REAL-0001",
                        "테스트 상품",
                        2,
                        10_000,
                        20_000,
                        "COMPLETED",
                        null,
                        asOf,
                        asOf,
                        asOf,
                        asOf,
                        asOf,
                        null,
                        null)),
                List.of(),
                java.util.Optional.empty(),
                false,
                asOf));
    ChatCommand command =
        new ChatCommand(
            UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "ORD-REAL-0001 상품과 가격을 알려줘");
    ToolContext secureContext =
        new ToolContext(
            Map.of(
                AdminToolExecutionContext.KEY,
                new AdminToolExecutionContext(
                    command.requestId(), command.message(), new RecordingSink())));

    AdminToolResult result = tools.lookupOrder("ORD-REAL-0001", true, false, false, secureContext);

    AdminDataTools.OrderLookupFacts facts = (AdminDataTools.OrderLookupFacts) result.data();
    assertThat(facts.snapshot())
        .extracting(
            AdminDataTools.OrderSnapshotFact::productName,
            AdminDataTools.OrderSnapshotFact::quantity,
            AdminDataTools.OrderSnapshotFact::unitPrice,
            AdminDataTools.OrderSnapshotFact::totalPrice)
        .containsExactly("테스트 상품", 2, 10_000L, 20_000L);
  }

  @Test
  @DisplayName("결제기한이 지난 결제대기 주문은 고정 지표로 반환한다")
  void countExpiredPaymentPendingOrders_returnsMetricFacts() {
    given(queryPort.isAvailable()).willReturn(true);
    given(queryPort.countExpiredPaymentPendingOrders())
        .willReturn(new AdminDataQueryResult.Metric(3, Instant.parse("2026-07-24T00:00:00Z")));

    AdminToolResult result = tools.countExpiredPaymentPendingOrders(toolContext);

    assertThat(result.status()).isEqualTo(AdminToolResult.Status.SUCCESS);
    assertThat(result.data()).isInstanceOf(AdminDataTools.MetricFacts.class);
    assertThat(((AdminDataTools.MetricFacts) result.data()).value()).isEqualTo(3);
    assertThat(sink.eventNames()).containsExactly("status");
  }

  private static final class RecordingSink implements ChatEventSink {

    private final java.util.ArrayList<com.openat.chat.application.dto.ChatStreamEvent> events =
        new java.util.ArrayList<>();
    private boolean closed;

    @Override
    public void emit(com.openat.chat.application.dto.ChatStreamEvent event) {
      events.add(event);
    }

    @Override
    public boolean terminate(
        Function<Boolean, com.openat.chat.application.dto.ChatStreamEvent> eventFactory) {
      if (closed) {
        return false;
      }
      events.add(eventFactory.apply(false));
      closed = true;
      return true;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    List<String> eventNames() {
      return events.stream().map(com.openat.chat.application.dto.ChatStreamEvent::name).toList();
    }
  }
}
