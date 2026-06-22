package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.ChargeConfirmCommand;
import com.openat.payment.application.dto.ChargePgCommand;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 순수 Mockito 단위테스트(A13 목표) — plan.md D2/E1. PaymentServiceTest의 멱등/confirmPg 패턴을 충전에 재적용.
class WalletChargeServiceTest {

    private final WalletChargeRepository walletChargeRepository = mock(WalletChargeRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);
    private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);

    private WalletChargeService walletChargeService;

    private UUID memberId;
    private Long amount;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        walletChargeService = new WalletChargeService(
                walletChargeRepository, walletRepository, walletTransactionRepository, tossPaymentClient);

        memberId = UUID.randomUUID();
        amount = 5_000L;
        idempotencyKey = "charge-idem-" + UUID.randomUUID();

        when(walletChargeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void MOCK_충전_정상_흐름이면_잔액을_증가시키고_즉시_APPROVED로_저장한다() {
        ChargeWalletCommand command = new ChargeWalletCommand(memberId, amount, idempotencyKey);
        when(walletChargeRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(walletRepository.findByMemberId(memberId)).thenReturn(Optional.empty());

        WalletChargeResult result = walletChargeService.chargeMock(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.amount()).isEqualTo(amount);
        verify(walletRepository).charge(any(), eq(amount));
    }

    @Test
    void MOCK_충전_같은_멱등키_같은_바디로_재요청하면_기존_결과를_그대로_반환한다() {
        ChargeWalletCommand command = new ChargeWalletCommand(memberId, amount, idempotencyKey);
        String requestHash = RequestHasher.hash(memberId.toString(), amount.toString(), WalletCharge.Method.MOCK.name());
        WalletCharge existing = WalletCharge.builder()
                .memberId(memberId).amount(amount).method(WalletCharge.Method.MOCK)
                .status(WalletCharge.Status.APPROVED)
                .idempotencyKey(idempotencyKey).requestHash(requestHash)
                .build();
        when(walletChargeRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        WalletChargeResult result = walletChargeService.chargeMock(command);

        assertThat(result.chargeId()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo("APPROVED");
        verify(walletRepository, never()).charge(any(), any());
    }

    @Test
    void MOCK_충전_같은_멱등키_다른_바디로_재요청하면_IDEMPOTENCY_KEY_CONFLICT_예외가_발생한다() {
        ChargeWalletCommand command = new ChargeWalletCommand(memberId, amount, idempotencyKey);
        String differentBodyHash = RequestHasher.hash(memberId.toString(), "1", WalletCharge.Method.MOCK.name());
        WalletCharge existing = WalletCharge.builder()
                .memberId(memberId).amount(1L).method(WalletCharge.Method.MOCK)
                .status(WalletCharge.Status.APPROVED)
                .idempotencyKey(idempotencyKey).requestHash(differentBodyHash)
                .build();
        when(walletChargeRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> walletChargeService.chargeMock(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    // ---------- chargePg ----------

    @Test
    void PG_충전_요청은_PENDING_row만_만들고_PG는_전혀_호출하지_않는다() {
        ChargePgCommand command = new ChargePgCommand(memberId, amount, idempotencyKey);
        when(walletChargeRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

        WalletChargeResult result = walletChargeService.chargePg(command);

        assertThat(result.status()).isEqualTo("PENDING");
        verifyNoInteractions(tossPaymentClient);
    }

    // ---------- confirmCharge ----------

    @Test
    void confirmCharge_정상_승인이면_pgPaymentKey_선기록후_조건부UPDATE로_확정하고_Wallet_잔액을_증가시킨다() {
        UUID chargeId = UUID.randomUUID();
        String paymentKey = "toss-payment-key";
        WalletCharge pending = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();
        WalletCharge approved = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.APPROVED)
                .idempotencyKey(idempotencyKey)
                .build();
        when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(pending), Optional.of(approved));
        when(tossPaymentClient.confirmCharge(paymentKey, chargeId, amount, idempotencyKey))
                .thenReturn(TossConfirmResult.approved("toss-tx-1"));
        when(walletChargeRepository.tryTransitionFromPending(chargeId, WalletCharge.Status.APPROVED, "toss-tx-1"))
                .thenReturn(1);
        when(walletRepository.findByMemberId(memberId)).thenReturn(Optional.empty());

        ChargeConfirmCommand command = new ChargeConfirmCommand(chargeId, memberId, amount, paymentKey, idempotencyKey);
        WalletChargeResult result = walletChargeService.confirmCharge(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        verify(walletChargeRepository).updatePgPaymentKey(chargeId, paymentKey);
        verify(walletRepository).charge(any(), eq(amount));
        verify(walletTransactionRepository).save(any());
    }

    @Test
    void confirmCharge_PG가_거절하면_FAILED로_확정하고_Wallet은_건드리지_않는다() {
        UUID chargeId = UUID.randomUUID();
        String paymentKey = "toss-payment-key";
        WalletCharge pending = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();
        WalletCharge failed = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.FAILED)
                .idempotencyKey(idempotencyKey)
                .build();
        when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(pending), Optional.of(failed));
        when(tossPaymentClient.confirmCharge(paymentKey, chargeId, amount, idempotencyKey))
                .thenReturn(TossConfirmResult.rejected("PG_REJECTED"));
        when(walletChargeRepository.tryTransitionFromPending(eq(chargeId), eq(WalletCharge.Status.FAILED), isNull()))
                .thenReturn(1);

        ChargeConfirmCommand command = new ChargeConfirmCommand(chargeId, memberId, amount, paymentKey, idempotencyKey);
        WalletChargeResult result = walletChargeService.confirmCharge(command);

        assertThat(result.status()).isEqualTo("FAILED");
        verifyNoInteractions(walletRepository);
        verifyNoInteractions(walletTransactionRepository);
    }

    @Test
    void confirmCharge_이미_종결된_충전이면_PG를_재호출하지_않고_그_상태_그대로_반환한다() {
        UUID chargeId = UUID.randomUUID();
        WalletCharge alreadyApproved = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.APPROVED)
                .build();
        when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(alreadyApproved));

        ChargeConfirmCommand command = new ChargeConfirmCommand(chargeId, memberId, amount, "any-key", idempotencyKey);
        WalletChargeResult result = walletChargeService.confirmCharge(command);

        assertThat(result.status()).isEqualTo("APPROVED");
        verifyNoInteractions(tossPaymentClient);
        verify(walletChargeRepository, never()).updatePgPaymentKey(any(), any());
    }

    @Test
    void confirmCharge_대상_충전이_없으면_NOT_FOUND_예외가_발생한다() {
        UUID chargeId = UUID.randomUUID();
        when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.empty());

        ChargeConfirmCommand command = new ChargeConfirmCommand(chargeId, memberId, amount, "any-key", idempotencyKey);

        assertThatThrownBy(() -> walletChargeService.confirmCharge(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void confirmCharge_소유자가_다르면_FORBIDDEN_예외가_발생하고_PG를_호출하지_않는다() {
        UUID chargeId = UUID.randomUUID();
        WalletCharge pending = WalletCharge.builder()
                .id(chargeId).memberId(UUID.randomUUID()).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.PENDING)
                .build();
        when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(pending));

        ChargeConfirmCommand command = new ChargeConfirmCommand(chargeId, memberId, amount, "any-key", idempotencyKey);

        assertThatThrownBy(() -> walletChargeService.confirmCharge(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.FORBIDDEN);

        verifyNoInteractions(tossPaymentClient);
    }

    @Test
    void confirmCharge_조건부UPDATE가_레이스에서_패배하면_자신이_계산한_상태_대신_현재_상태를_반환한다() {
        UUID chargeId = UUID.randomUUID();
        String paymentKey = "toss-payment-key";
        WalletCharge pending = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.PENDING)
                .build();
        WalletCharge finalizedByOther = WalletCharge.builder()
                .id(chargeId).memberId(memberId).amount(amount)
                .method(WalletCharge.Method.PG).status(WalletCharge.Status.FAILED)
                .build();
        when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(pending), Optional.of(finalizedByOther));
        when(tossPaymentClient.confirmCharge(paymentKey, chargeId, amount, idempotencyKey))
                .thenReturn(TossConfirmResult.approved("toss-tx-1"));
        when(walletChargeRepository.tryTransitionFromPending(chargeId, WalletCharge.Status.APPROVED, "toss-tx-1"))
                .thenReturn(0);

        ChargeConfirmCommand command = new ChargeConfirmCommand(chargeId, memberId, amount, paymentKey, idempotencyKey);
        WalletChargeResult result = walletChargeService.confirmCharge(command);

        assertThat(result.status()).isEqualTo("FAILED");
        verifyNoInteractions(walletRepository);
    }
}
