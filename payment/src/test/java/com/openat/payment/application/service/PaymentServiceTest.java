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
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 프레임워크(Spring) 의존 없는 순수 Mockito 단위테스트(A13 목표) — plan.md D1.
// 전체 커버리지가 아니라 plan.md A9의 하자드(#7·#8·#10·#13·#20) 가드가 실제로 호출되는지를 검증.
// confirmPg는 7-13 plan WS-C로 재작성(get-or-create 예약) — 해당 동작은 PaymentServiceConfirmTest.
class PaymentServiceTest {

  private static final String COMPLETED_TOPIC = "payment.completed.events";
  private static final String SETTLEMENT_SOURCE_TOPIC = "payment.settlement.events";

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletRepository walletRepository = mock(WalletRepository.class);
  private final WalletTransactionRepository walletTransactionRepository =
      mock(WalletTransactionRepository.class);
  private final PaymentEventRepository paymentEventRepository = mock(PaymentEventRepository.class);
  private final OrderValidationClient orderValidationClient = mock(OrderValidationClient.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
  private final PaymentFinalizer paymentFinalizer = mock(PaymentFinalizer.class);

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
            walletRepository,
            walletTransactionRepository,
            paymentEventRepository,
            orderValidationClient,
            tossPaymentClient,
            eventPublisher,
            paymentFinalizer);

    orderId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 10_000L;
    idempotencyKey = "idem-" + UUID.randomUUID();

    // 빌더가 만든 객체를 그대로 돌려주는 저장 스텁 — 대부분의 케이스에서 재사용.
    when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(paymentEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  // ---------- payWithWallet ----------

  @Test
  void WALLET_결제_정상_흐름이면_잔액차감_저장_completed이벤트_발행까지_수행된다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
    Wallet wallet = Wallet.builder().memberId(memberId).balance(10_000L).build();
    when(walletRepository.findOrCreateByMemberId(memberId)).thenReturn(wallet);
    when(walletRepository.tryDeduct(any(), eq(amount))).thenReturn(1);
    when(walletRepository.findByMemberId(memberId))
        .thenReturn(Optional.of(Wallet.builder().memberId(memberId).balance(0L).build()));

    PaymentResult result = paymentService.payWithWallet(command);

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.amount()).isEqualTo(amount);
    verify(eventPublisher)
        .publish(eq("PAYMENT"), any(), eq(COMPLETED_TOPIC), any(PaymentCompletedPayload.class));
  }

  @Test
  void WALLET_결제시_잔액부족이면_INSUFFICIENT_BALANCE_예외가_발생하고_이후_단계는_호출되지_않는다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
    when(walletRepository.findOrCreateByMemberId(memberId))
        .thenReturn(Wallet.builder().memberId(memberId).balance(0L).build());
    when(walletRepository.tryDeduct(any(), eq(amount))).thenReturn(0);

    assertThatThrownBy(() -> paymentService.payWithWallet(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.INSUFFICIENT_BALANCE);

    verify(paymentRepository, never()).save(any());
    verify(eventPublisher, never()).publish(any(), any(), any(), any());
  }

  @Test
  void WALLET_결제시_Order_검증에_실패하면_ORDER_VALIDATION_FAILED_예외가_발생하고_잔액차감을_시도하지_않는다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "REJECTED", false));

    assertThatThrownBy(() -> paymentService.payWithWallet(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.ORDER_VALIDATION_FAILED);

    verify(walletRepository, never()).tryDeduct(any(), any());
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
    verify(walletRepository, never()).tryDeduct(any(), any());
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
