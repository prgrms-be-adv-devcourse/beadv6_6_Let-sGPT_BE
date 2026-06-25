package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.*;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import com.openat.payment.infrastructure.outbox.OutboxEventWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 프레임워크(Spring) 의존 없는 순수 Mockito 단위테스트(A13 목표) — plan.md D1.
// 전체 커버리지가 아니라 plan.md A9의 하자드(#7·#8·#10·#13·#20) 가드가 실제로 호출되는지를 검증.
class PaymentServiceTest {

    private static final String COMPLETED_TOPIC = "payment.completed.events";
    private static final String FAILED_TOPIC = "payment.failed.events";
    private static final String SETTLEMENT_SOURCE_TOPIC = "payment.settlement-source.events";

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);
    private final PaymentEventRepository paymentEventRepository = mock(PaymentEventRepository.class);
    private final OrderValidationClient orderValidationClient = mock(OrderValidationClient.class);
    private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
    private final OutboxEventWriter outboxEventWriter = mock(OutboxEventWriter.class);

    private PaymentService paymentService;

    private UUID orderId;
    private UUID memberId;
    private Long amount;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, walletRepository, walletTransactionRepository,
                paymentEventRepository, orderValidationClient, tossPaymentClient, outboxEventWriter);

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
        PayWithWalletCommand command = new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
        when(walletRepository.findByMemberId(memberId)).thenReturn(Optional.empty());
        when(walletRepository.tryDeduct(any(), eq(amount))).thenReturn(1);

        PaymentResult result = paymentService.payWithWallet(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.amount()).isEqualTo(amount);
        verify(outboxEventWriter).write(eq("PAYMENT"), any(), eq(COMPLETED_TOPIC), any(PaymentCompletedPayload.class));
    }

    @Test
    void WALLET_결제시_잔액부족이면_INSUFFICIENT_BALANCE_예외가_발생하고_이후_단계는_호출되지_않는다() {
        PayWithWalletCommand command = new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
        when(walletRepository.findByMemberId(memberId)).thenReturn(Optional.empty());
        when(walletRepository.tryDeduct(any(), eq(amount))).thenReturn(0);

        assertThatThrownBy(() -> paymentService.payWithWallet(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.INSUFFICIENT_BALANCE);

        verify(paymentRepository, never()).save(any());
        verify(outboxEventWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void WALLET_결제시_Order_검증에_실패하면_ORDER_VALIDATION_FAILED_예외가_발생하고_잔액차감을_시도하지_않는다() {
        PayWithWalletCommand command = new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
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
        PayWithWalletCommand command = new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
        String requestHash = RequestHasher.hash(
                orderId.toString(), memberId.toString(), amount.toString(), Payment.Method.WALLET.name());
        Payment existing = Payment.builder()
                .orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.WALLET).status(Payment.Status.APPROVED)
                .idempotencyKey(idempotencyKey).requestHash(requestHash)
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
        PayWithWalletCommand command = new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
        String differentBodyHash = RequestHasher.hash(
                orderId.toString(), memberId.toString(), "9999", Payment.Method.WALLET.name());
        Payment existing = Payment.builder()
                .orderId(orderId).memberId(memberId).amount(9_999L)
                .method(Payment.Method.WALLET).status(Payment.Status.APPROVED)
                .idempotencyKey(idempotencyKey).requestHash(differentBodyHash)
                .build();
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> paymentService.payWithWallet(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    // ---------- payWithPg ----------

    @Test
    void PG_결제_요청은_PENDING_row만_만들고_PG는_전혀_호출하지_않는다() {
        PayWithPgCommand command = new PayWithPgCommand(orderId, memberId, amount, idempotencyKey);
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));

        PaymentResult result = paymentService.payWithPg(command);

        assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
        verifyNoInteractions(tossPaymentClient);
        verify(outboxEventWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void PG_결제_요청도_같은_멱등키_다른_바디면_IDEMPOTENCY_KEY_CONFLICT_예외가_발생한다() {
        PayWithPgCommand command = new PayWithPgCommand(orderId, memberId, amount, idempotencyKey);
        String differentBodyHash = RequestHasher.hash(
                orderId.toString(), memberId.toString(), "1", Payment.Method.PG.name());
        Payment existing = Payment.builder()
                .orderId(orderId).memberId(memberId).amount(1L)
                .method(Payment.Method.PG).status(Payment.Status.PAYMENT_PENDING)
                .idempotencyKey(idempotencyKey).requestHash(differentBodyHash)
                .build();
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> paymentService.payWithPg(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    // ---------- confirmPg ----------

    @Test
    void confirmPg_정상_승인이면_pgPaymentKey_선기록후_조건부UPDATE로_확정하고_completed이벤트를_발행한다() {
        UUID paymentId = UUID.randomUUID();
        String paymentKey = "toss-payment-key";
        Payment pending = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.PAYMENT_PENDING)
                .build();
        Payment approved = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.APPROVED)
                .pgTxId("toss-tx-1").approvedAt(LocalDateTime.now())
                .build();
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
                .thenReturn(Optional.of(pending));
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
        when(tossPaymentClient.confirmPayment(paymentKey, orderId, amount, idempotencyKey))
                .thenReturn(TossConfirmResult.approved("toss-tx-1"));
        when(paymentRepository.tryTransitionFromPending(eq(paymentId), eq(Payment.Status.APPROVED), eq("toss-tx-1"), any()))
                .thenReturn(1);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(approved));

        PgConfirmCommand command = new PgConfirmCommand(orderId, memberId, amount, paymentKey, idempotencyKey);
        PaymentResult result = paymentService.confirmPg(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(paymentRepository).updatePgPaymentKey(paymentId, paymentKey);
        verify(outboxEventWriter).write(eq("PAYMENT"), eq(paymentId), eq(COMPLETED_TOPIC), any(PaymentCompletedPayload.class));
    }

    @Test
    void confirmPg_PG가_거절하면_FAILED로_확정하고_failed이벤트에_거절사유를_담아_발행한다() {
        UUID paymentId = UUID.randomUUID();
        String paymentKey = "toss-payment-key";
        Payment pending = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.PAYMENT_PENDING)
                .build();
        Payment failed = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.FAILED)
                .build();
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
                .thenReturn(Optional.of(pending));
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
        when(tossPaymentClient.confirmPayment(paymentKey, orderId, amount, idempotencyKey))
                .thenReturn(TossConfirmResult.rejected("PG_REJECTED"));
        when(paymentRepository.tryTransitionFromPending(eq(paymentId), eq(Payment.Status.FAILED), isNull(), isNull()))
                .thenReturn(1);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(failed));

        PgConfirmCommand command = new PgConfirmCommand(orderId, memberId, amount, paymentKey, idempotencyKey);
        PaymentResult result = paymentService.confirmPg(command);

        assertThat(result.status()).isEqualTo("FAILED");
        org.mockito.ArgumentCaptor<PaymentFailedPayload> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentFailedPayload.class);
        verify(outboxEventWriter).write(eq("PAYMENT"), eq(paymentId), eq(FAILED_TOPIC), captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("PG_REJECTED");
    }

    @Test
    void confirmPg_이미_종결된_결제면_PG를_재호출하지_않고_그_상태_그대로_반환한다() {
        Payment alreadyApproved = Payment.builder()
                .orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.APPROVED)
                .build();
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
                .thenReturn(Optional.of(alreadyApproved));

        PgConfirmCommand command = new PgConfirmCommand(orderId, memberId, amount, "any-key", idempotencyKey);
        PaymentResult result = paymentService.confirmPg(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        verifyNoInteractions(orderValidationClient, tossPaymentClient);
        verify(paymentRepository, never()).updatePgPaymentKey(any(), any());
    }

    @Test
    void confirmPg_대상_결제가_세_상태_어디에도_없으면_NOT_FOUND_예외가_발생한다() {
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
                .thenReturn(Optional.empty());
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.FAILED))
                .thenReturn(Optional.empty());

        PgConfirmCommand command = new PgConfirmCommand(orderId, memberId, amount, "any-key", idempotencyKey);

        assertThatThrownBy(() -> paymentService.confirmPg(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void confirmPg_Order_재검증에_실패하면_pgPaymentKey_선기록과_PG호출_모두_일어나지_않는다() {
        Payment pending = Payment.builder()
                .orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.PAYMENT_PENDING)
                .build();
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
                .thenReturn(Optional.of(pending));
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "REJECTED", false));

        PgConfirmCommand command = new PgConfirmCommand(orderId, memberId, amount, "any-key", idempotencyKey);

        assertThatThrownBy(() -> paymentService.confirmPg(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.ORDER_VALIDATION_FAILED);

        verify(paymentRepository, never()).updatePgPaymentKey(any(), any());
        verifyNoInteractions(tossPaymentClient);
    }

    @Test
    void confirmPg_조건부UPDATE가_레이스에서_패배하면_자신이_계산한_상태_대신_현재_DB상태를_반환한다() {
        UUID paymentId = UUID.randomUUID();
        String paymentKey = "toss-payment-key";
        Payment pending = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.PAYMENT_PENDING)
                .build();
        // 보조 웹훅/TTL스캐너가 먼저 확정시켜버린 "현재 상태" — 이 스레드가 계산한 APPROVED와 다름.
        Payment finalizedByOther = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .method(Payment.Method.PG).status(Payment.Status.FAILED)
                .build();
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
                .thenReturn(Optional.of(pending));
        when(orderValidationClient.validate(orderId, memberId, amount))
                .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
        when(tossPaymentClient.confirmPayment(paymentKey, orderId, amount, idempotencyKey))
                .thenReturn(TossConfirmResult.approved("toss-tx-1"));
        when(paymentRepository.tryTransitionFromPending(eq(paymentId), eq(Payment.Status.APPROVED), eq("toss-tx-1"), any()))
                .thenReturn(0);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(finalizedByOther));

        PgConfirmCommand command = new PgConfirmCommand(orderId, memberId, amount, paymentKey, idempotencyKey);
        PaymentResult result = paymentService.confirmPg(command);

        assertThat(result.status()).isEqualTo("FAILED");
        verify(outboxEventWriter, never()).write(any(), any(), any(), any());
    }

    // ---------- backfillSellerAndProduct ----------

    @Test
    void backfillSellerAndProduct_정상_사후채움이면_settlement_source_이벤트를_올바른_필드매핑으로_발행한다() {
        UUID paymentId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Payment filled = Payment.builder()
                .id(paymentId).orderId(orderId).memberId(memberId).amount(amount)
                .sellerId(sellerId).productId(productId)
                .method(Payment.Method.WALLET).status(Payment.Status.APPROVED)
                .refundedAmount(0L).approvedAt(LocalDateTime.now())
                .build();
        when(paymentRepository.tryFillSellerAndProduct(orderId, sellerId, productId)).thenReturn(1);
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
                .thenReturn(Optional.of(filled));

        paymentService.backfillSellerAndProduct(orderId, sellerId, productId);

        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter).write(eq("PAYMENT"), eq(paymentId), eq(SETTLEMENT_SOURCE_TOPIC), captor.capture());
        Object payload = captor.getValue();
        assertThat(recordValue(payload, "sellerId")).isEqualTo(sellerId);
        assertThat(recordValue(payload, "buyerId")).isEqualTo(memberId);
        assertThat(recordValue(payload, "productId")).isEqualTo(productId);
        assertThat(recordValue(payload, "orderId")).isEqualTo(orderId);
    }

    @Test
    void backfillSellerAndProduct_이미_채워졌거나_대상이_없으면_조회와_발행_모두_하지_않는다() {
        when(paymentRepository.tryFillSellerAndProduct(orderId, UUID.randomUUID(), UUID.randomUUID())).thenReturn(0);

        paymentService.backfillSellerAndProduct(orderId, UUID.randomUUID(), UUID.randomUUID());

        verify(paymentRepository, never()).findByOrderIdAndStatus(any(), any());
        verify(outboxEventWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void backfillSellerAndProduct_업데이트는_됐는데_재조회가_비어있으면_조용히_종료한다() {
        UUID sellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(paymentRepository.tryFillSellerAndProduct(orderId, sellerId, productId)).thenReturn(1);
        when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED)).thenReturn(Optional.empty());

        paymentService.backfillSellerAndProduct(orderId, sellerId, productId);

        verify(outboxEventWriter, never()).write(any(), any(), any(), any());
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
