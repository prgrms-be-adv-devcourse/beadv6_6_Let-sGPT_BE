package com.openat.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.settlement.application.dto.DailyReconciliationSummary;
import com.openat.settlement.application.dto.RecordPaymentCompletedCommand;
import com.openat.settlement.application.dto.RecordPaymentRefundedCommand;
import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementRefund;
import com.openat.settlement.domain.repository.SettlementOrderRepository;
import com.openat.settlement.domain.repository.SettlementRefundRepository;
import com.openat.settlement.infrastructure.client.PaymentReconciliationClient;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse.PaymentItem;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse.RefundItem;
import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse.Summary;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationDiscrepancyJpaRepository;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyReconciliationServiceTest {

  @Mock private PaymentReconciliationClient paymentReconciliationClient;
  @Mock private PaymentSettlementEventService paymentSettlementEventService;
  @Mock private SettlementOrderRepository settlementOrderRepository;
  @Mock private SettlementRefundRepository settlementRefundRepository;
  @Mock private DailyReconciliationResultJpaRepository resultRepository;
  @Mock private DailyReconciliationDiscrepancyJpaRepository discrepancyRepository;
  @Mock private SettlementOrder settlementOrder;
  @Mock private SettlementRefund settlementRefund;

  private DailyReconciliationService service;

  @BeforeEach
  void setUp() {
    service =
        new DailyReconciliationService(
            paymentReconciliationClient,
            paymentSettlementEventService,
            settlementOrderRepository,
            settlementRefundRepository,
            resultRepository,
            discrepancyRepository);
  }

  @Test
  void missingPaymentAndRefundAreBackfilledBeforeComparison() {
    LocalDate businessDate = LocalDate.of(2026, 7, 14);
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID refundId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();

    PaymentItem payment =
        new PaymentItem(
            paymentId,
            orderId,
            sellerId,
            buyerId,
            productId,
            10_000L,
            10_000L,
            0L,
            "APPROVED",
            OffsetDateTime.of(2026, 7, 14, 12, 0, 0, 0, ZoneOffset.ofHours(9)),
            "202607");
    RefundItem refund =
        new RefundItem(
            refundId,
            paymentId,
            orderId,
            sellerId,
            buyerId,
            2_000L,
            "partial refund",
            "COMPLETE",
            OffsetDateTime.of(2026, 7, 14, 13, 0, 0, 0, ZoneOffset.ofHours(9)),
            "202607");
    DailyPaymentSettlementResponse response =
        new DailyPaymentSettlementResponse(
            businessDate.toString(),
            List.of(payment),
            List.of(refund),
            new Summary(1, 10_000L, 1, 2_000L, 8_000L));

    when(paymentReconciliationClient.getDaily(businessDate)).thenReturn(response);
    when(settlementOrderRepository.findByOrderId(orderId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(settlementOrder));
    when(settlementOrder.getPaidAmount()).thenReturn(10_000L);
    when(settlementRefundRepository.findByRefundId(refundId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(settlementRefund));
    when(settlementRefund.getRefundAmount()).thenReturn(2_000L);
    when(resultRepository.findByBusinessDate(businessDate)).thenReturn(Optional.empty());

    DailyReconciliationSummary result = service.reconcile(businessDate);

    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(result.discrepancyCount()).isZero();
    verify(paymentSettlementEventService)
        .upsertSettlementOrder(any(RecordPaymentCompletedCommand.class));
    verify(paymentSettlementEventService)
        .saveSettlementRefund(any(RecordPaymentRefundedCommand.class));
  }

  @Test
  void existingAmountMismatchIsNotOverwritten() {
    LocalDate businessDate = LocalDate.of(2026, 7, 14);
    UUID paymentId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    PaymentItem payment =
        new PaymentItem(
            paymentId,
            orderId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            10_000L,
            10_000L,
            0L,
            "APPROVED",
            OffsetDateTime.of(2026, 7, 14, 12, 0, 0, 0, ZoneOffset.ofHours(9)),
            "202607");
    DailyPaymentSettlementResponse response =
        new DailyPaymentSettlementResponse(
            businessDate.toString(),
            List.of(payment),
            List.of(),
            new Summary(1, 10_000L, 0, 0L, 10_000L));

    when(paymentReconciliationClient.getDaily(businessDate)).thenReturn(response);
    when(settlementOrderRepository.findByOrderId(orderId)).thenReturn(Optional.of(settlementOrder));
    when(settlementOrder.getPaidAmount()).thenReturn(9_000L);
    when(resultRepository.findByBusinessDate(businessDate)).thenReturn(Optional.empty());

    DailyReconciliationSummary result = service.reconcile(businessDate);

    assertThat(result.status()).isEqualTo("DISCREPANCY_FOUND");
    assertThat(result.discrepancyCount()).isEqualTo(1);
    verifyNoInteractions(paymentSettlementEventService);
  }
}
