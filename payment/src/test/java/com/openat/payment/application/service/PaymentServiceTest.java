package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.*;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 프레임워크(Spring) 의존 없는 순수 Mockito 단위테스트(A13 목표) — plan.md D1.
// PaymentService는 오케스트레이션(멱등 조회 -> Order 검증 -> WalletPaymentApprover 위임)만 담당한다.
// WALLET 쓰기 경로(차감·저장·이벤트)의 상세 검증은 WalletPaymentApproverTest로 이관.
// confirmPg는 7-13 plan WS-C로 재작성(get-or-create 예약) — 해당 동작은 PaymentServiceConfirmTest.
class PaymentServiceTest {

  private static final String SETTLEMENT_SOURCE_TOPIC = "payment.settlement.events";

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final OrderValidationClient orderValidationClient = mock(OrderValidationClient.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
  private final PaymentFinalizer paymentFinalizer = mock(PaymentFinalizer.class);
  private final WalletPaymentApprover walletPaymentApprover = mock(WalletPaymentApprover.class);

  private PaymentService paymentService;

  private UUID orderId;
  private UUID memberId;
  private Long amount;
  private String idempotencyKey;

  @BeforeEach
  void setUp() {
    paymentService =
        new PaymentService(
            paymentRepository,
            orderValidationClient,
            tossPaymentClient,
            eventPublisher,
            paymentFinalizer,
            walletPaymentApprover,
            new SimpleMeterRegistry());

    orderId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 10_000L;
    idempotencyKey = "idem-" + UUID.randomUUID();
  }

  // ---------- payWithWallet ----------

  @Test
  void WALLET_결제_정상_흐름이면_WalletPaymentApprover에_위임하고_결과를_반환한다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
    Payment approved =
        Payment.builder()
            .id(UUID.randomUUID())
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .idempotencyKey(idempotencyKey)
            .build();
    when(walletPaymentApprover.deductAndApprove(any(), any())).thenReturn(approved);

    PaymentResult result = paymentService.payWithWallet(command);

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.amount()).isEqualTo(amount);
    verify(walletPaymentApprover).deductAndApprove(eq(command), any());
    // completed 이벤트 발행 검증은 approver 책임 — WalletPaymentApproverTest 참조.
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void WALLET_결제시_잔액부족이면_approver가_던진_INSUFFICIENT_BALANCE_예외가_전파된다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
    when(walletPaymentApprover.deductAndApprove(any(), any()))
        .thenThrow(new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE));

    assertThatThrownBy(() -> paymentService.payWithWallet(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.INSUFFICIENT_BALANCE);
  }

  @Test
  void WALLET_결제시_Order_검증에_실패하면_ORDER_VALIDATION_FAILED_예외가_발생하고_approver를_호출하지_않는다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "REJECTED", false));

    assertThatThrownBy(() -> paymentService.payWithWallet(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.ORDER_VALIDATION_FAILED);

    verify(walletPaymentApprover, never()).deductAndApprove(any(), any());
  }

  @Test
  void WALLET_결제_같은_멱등키_같은_바디로_재요청하면_기존_결과를_그대로_반환한다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    String requestHash =
        RequestHasher.hash(
            orderId.toString(),
            memberId.toString(),
            amount.toString(),
            Payment.Method.WALLET.name());
    Payment existing =
        Payment.builder()
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .idempotencyKey(idempotencyKey)
            .requestHash(requestHash)
            .build();
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

    PaymentResult result = paymentService.payWithWallet(command);

    assertThat(result.paymentId()).isEqualTo(existing.getId());
    assertThat(result.status()).isEqualTo("APPROVED");
    verifyNoInteractions(orderValidationClient);
    verify(walletPaymentApprover, never()).deductAndApprove(any(), any());
  }

  @Test
  void WALLET_결제_같은_멱등키_다른_바디로_재요청하면_IDEMPOTENCY_KEY_CONFLICT_예외가_발생한다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    String differentBodyHash =
        RequestHasher.hash(
            orderId.toString(), memberId.toString(), "9999", Payment.Method.WALLET.name());
    Payment existing =
        Payment.builder()
            .orderId(orderId)
            .memberId(memberId)
            .amount(9_999L)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .idempotencyKey(idempotencyKey)
            .requestHash(differentBodyHash)
            .build();
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> paymentService.payWithWallet(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);

    verify(walletPaymentApprover, never()).deductAndApprove(any(), any());
  }

  // payWithPg는 7-13 plan D1로 제거(PG는 confirm 단일 진입점). confirmPg 신규 동작은 PaymentServiceConfirmTest.

  // ---------- backfillSellerAndProduct ----------

  @Test
  void backfillSellerAndProduct_정상_사후채움이면_settlement_source_이벤트를_올바른_필드매핑으로_발행한다() {
    UUID paymentId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    Payment filled =
        Payment.builder()
            .id(paymentId)
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .sellerId(sellerId)
            .productId(productId)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.tryFillSellerAndProduct(orderId, sellerId, productId)).thenReturn(1);
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
        .thenReturn(Optional.of(filled));

    paymentService.backfillSellerAndProduct(orderId, sellerId, productId);

    org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher)
        .publish(eq("PAYMENT"), eq(paymentId), eq(SETTLEMENT_SOURCE_TOPIC), captor.capture());
    Object payload = captor.getValue();
    assertThat(recordValue(payload, "sellerId")).isEqualTo(sellerId);
    assertThat(recordValue(payload, "buyerId")).isEqualTo(memberId);
    assertThat(recordValue(payload, "productId")).isEqualTo(productId);
    assertThat(recordValue(payload, "orderId")).isEqualTo(orderId);
  }

  @Test
  void backfillSellerAndProduct_이미_채워졌거나_대상이_없으면_조회와_발행_모두_하지_않는다() {
    when(paymentRepository.tryFillSellerAndProduct(orderId, UUID.randomUUID(), UUID.randomUUID()))
        .thenReturn(0);

    paymentService.backfillSellerAndProduct(orderId, UUID.randomUUID(), UUID.randomUUID());

    verify(paymentRepository, never()).findByOrderIdAndStatus(any(), any());
    verify(eventPublisher, never()).publish(any(), any(), any(), any());
  }

  @Test
  void backfillSellerAndProduct_업데이트는_됐는데_재조회가_비어있으면_조용히_종료한다() {
    UUID sellerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    when(paymentRepository.tryFillSellerAndProduct(orderId, sellerId, productId)).thenReturn(1);
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
        .thenReturn(Optional.empty());

    paymentService.backfillSellerAndProduct(orderId, sellerId, productId);

    verify(eventPublisher, never()).publish(any(), any(), any(), any());
  }

  // record가 PaymentService 내부 private이라 리플렉션으로 필드값을 읽는다.
  private static Object recordValue(Object record, String componentName) {
    try {
      for (RecordComponent component : record.getClass().getRecordComponents()) {
        if (component.getName().equals(componentName)) {
          component.getAccessor().setAccessible(true);
          return component.getAccessor().invoke(record);
        }
      }
      throw new IllegalArgumentException("no such record component: " + componentName);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}
