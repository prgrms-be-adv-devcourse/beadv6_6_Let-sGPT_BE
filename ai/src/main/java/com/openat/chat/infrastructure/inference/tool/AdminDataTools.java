package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.application.port.AdminDataQueryPort;
import com.openat.chat.domain.planning.PlanningDateTimeValidator;
import com.openat.chat.domain.query.AdminDataQueryPlan;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AdminDataTools {

  private static final Logger log = LoggerFactory.getLogger(AdminDataTools.class);

  private final AdminDataQueryPort queryPort;

  public AdminDataTools(AdminDataQueryPort queryPort) {
    this.queryPort = queryPort;
  }

  @Tool(
      name = "lookupOrder",
      description = "질문 원문에 명시된 공개 OPENAT 주문번호 한 건의 비식별 현재 상태, 처리 이벤트와 현재 사가를 조회한다.")
  public AdminToolResult lookupOrder(
      @ToolParam(description = "질문 원문에 있는 ORD- 형식 공개 주문번호") String orderNumber,
      @ToolParam(description = "현재 주문 상태 포함 여부") boolean includeSnapshot,
      @ToolParam(description = "처리 이벤트 포함 여부") boolean includeProcessEvents,
      @ToolParam(description = "현재 사가 포함 여부") boolean includeCurrentSaga,
      ToolContext toolContext) {
    AdminToolExecutionContext context = AdminToolContexts.required(toolContext);
    context.started();
    try {
      if (!queryPort.isAvailable()) {
        return context.completed(
            AdminToolResult.failed("DATA_SOURCE_UNAVAILABLE", "내부 데이터 조회가 현재 비활성화되어 있어요."));
      }
      String verifiedOrderNumber = context.verifiedOrderNumber(orderNumber);
      AdminDataQueryResult.OrderLookup result =
          queryPort.lookupOrder(
              new AdminDataQueryPlan.OrderLookup(
                  verifiedOrderNumber, includeSnapshot, includeProcessEvents, includeCurrentSaga));
      if (result.snapshot().isEmpty()) {
        return context.completed(
            AdminToolResult.failed("ORDER_NOT_FOUND", "해당 공개 주문번호의 주문을 찾지 못했어요."));
      }
      return context.completed(
          AdminToolResult.success(
              orderFacts(result, includeSnapshot, includeProcessEvents, includeCurrentSaga)));
    } catch (IllegalArgumentException exception) {
      return context.completed(
          AdminToolResult.failed("UNVERIFIED_ORDER_REFERENCE", exception.getMessage()));
    } catch (RuntimeException exception) {
      logFailure("lookupOrder", exception);
      return context.completed(
          AdminToolResult.failed("DATA_SOURCE_FAILED", "개별 주문 조회를 완료하지 못했어요."));
    }
  }

  @Tool(
      name = "countExpiredPaymentPendingOrders",
      description = "현재 결제기한이 지났지만 PAYMENT_PENDING 상태인 OPENAT 주문 수를 조회한다.")
  public AdminToolResult countExpiredPaymentPendingOrders(ToolContext toolContext) {
    AdminToolExecutionContext context = AdminToolContexts.required(toolContext);
    context.started();
    try {
      if (!queryPort.isAvailable()) {
        return context.completed(
            AdminToolResult.failed("DATA_SOURCE_UNAVAILABLE", "내부 데이터 조회가 현재 비활성화되어 있어요."));
      }
      AdminDataQueryResult.Metric result = queryPort.countExpiredPaymentPendingOrders();
      return context.completed(
          AdminToolResult.success(
              new MetricFacts(
                  "EXPIRED_PAYMENT_PENDING_ORDERS",
                  result.value(),
                  "건",
                  "결제기한 경과 && 현재 PAYMENT_PENDING",
                  kst(result.asOf()))));
    } catch (RuntimeException exception) {
      logFailure("countExpiredPaymentPendingOrders", exception);
      return context.completed(
          AdminToolResult.failed("DATA_SOURCE_FAILED", "결제기한 경과 주문 수를 조회하지 못했어요."));
    }
  }

  private OrderLookupFacts orderFacts(
      AdminDataQueryResult.OrderLookup result,
      boolean snapshotRequested,
      boolean processEventsRequested,
      boolean currentSagaRequested) {
    return new OrderLookupFacts(
        snapshotRequested ? "AVAILABLE" : "NOT_REQUESTED",
        snapshotRequested ? result.snapshot().map(this::snapshotFact).orElse(null) : null,
        processEventsRequested ? "AVAILABLE" : "NOT_REQUESTED",
        processEventsRequested
            ? result.processEvents().stream().map(this::eventFact).toList()
            : List.of(),
        currentSagaRequested
            ? (result.currentSaga().isPresent() ? "AVAILABLE" : "NOT_AVAILABLE")
            : "NOT_REQUESTED",
        currentSagaRequested ? result.currentSaga().map(this::sagaFact).orElse(null) : null,
        processEventsRequested && result.processEventsTruncated(),
        kst(result.asOf()));
  }

  private OrderSnapshotFact snapshotFact(AdminDataQueryResult.OrderSnapshot snapshot) {
    return new OrderSnapshotFact(
        snapshot.publicOrderNumber(),
        snapshot.productName(),
        snapshot.quantity(),
        snapshot.unitPrice(),
        snapshot.totalPrice(),
        snapshot.status(),
        snapshot.failCode(),
        kst(snapshot.paymentExpiresAt()),
        kst(snapshot.createdAt()),
        kst(snapshot.updatedAt()),
        kst(snapshot.paidAt()),
        kst(snapshot.completedAt()),
        kst(snapshot.cancelledAt()),
        kst(snapshot.refundedAt()));
  }

  private OrderEventFact eventFact(AdminDataQueryResult.OrderProcessEvent event) {
    return new OrderEventFact(
        event.sequence(),
        kst(event.occurredAt()),
        event.previousStatus(),
        event.newStatus(),
        event.reasonCode());
  }

  private OrderSagaFact sagaFact(AdminDataQueryResult.OrderSagaSnapshot saga) {
    return new OrderSagaFact(
        saga.currentStep(),
        kst(saga.compensatingSince()),
        kst(saga.createdAt()),
        kst(saga.updatedAt()));
  }

  private String kst(Instant instant) {
    if (instant == null) {
      return null;
    }
    return ZonedDateTime.ofInstant(instant, PlanningDateTimeValidator.SERVER_TIME_ZONE)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private void logFailure(String tool, RuntimeException exception) {
    log.warn("관리자 데이터 도구 실패 tool={}, errorType={}", tool, exception.getClass().getSimpleName());
  }

  public record MetricFacts(
      String metricId, long value, String unit, String definition, String asOf) {}

  public record OrderLookupFacts(
      String snapshotStatus,
      OrderSnapshotFact snapshot,
      String processEventsStatus,
      List<OrderEventFact> processEvents,
      String currentSagaStatus,
      OrderSagaFact currentSaga,
      boolean processEventsTruncated,
      String asOf) {}

  public record OrderSnapshotFact(
      String publicOrderNumber,
      String productName,
      int quantity,
      long unitPrice,
      long totalPrice,
      String status,
      String failCode,
      String paymentExpiresAt,
      String createdAt,
      String updatedAt,
      String paidAt,
      String completedAt,
      String cancelledAt,
      String refundedAt) {}

  public record OrderEventFact(
      long sequence,
      String occurredAt,
      String previousStatus,
      String newStatus,
      String reasonCode) {}

  public record OrderSagaFact(
      String currentStep, String compensatingSince, String createdAt, String updatedAt) {}
}
