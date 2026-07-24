package com.openat.chat.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class AdminDataQueryResult {

  private AdminDataQueryResult() {}

  public record Metric(long value, Instant asOf) {}

  public record OrderLookup(
      Optional<OrderSnapshot> snapshot,
      List<OrderProcessEvent> processEvents,
      Optional<OrderSagaSnapshot> currentSaga,
      boolean processEventsTruncated,
      Instant asOf) {

    public OrderLookup {
      snapshot = snapshot == null ? Optional.empty() : snapshot;
      processEvents = List.copyOf(processEvents);
      currentSaga = currentSaga == null ? Optional.empty() : currentSaga;
    }
  }

  public record OrderSnapshot(
      String publicOrderNumber,
      String productName,
      int quantity,
      long unitPrice,
      long totalPrice,
      String status,
      String failCode,
      Instant paymentExpiresAt,
      Instant createdAt,
      Instant updatedAt,
      Instant paidAt,
      Instant completedAt,
      Instant cancelledAt,
      Instant refundedAt) {}

  public record OrderProcessEvent(
      long sequence,
      Instant occurredAt,
      String previousStatus,
      String newStatus,
      String reasonCode) {}

  public record OrderSagaSnapshot(
      String currentStep, Instant compensatingSince, Instant createdAt, Instant updatedAt) {}
}
