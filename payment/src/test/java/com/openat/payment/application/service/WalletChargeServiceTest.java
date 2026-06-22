package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
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

// 순수 Mockito 단위테스트(A13 목표) — plan.md D2. PaymentServiceTest의 멱등 패턴(D1 1·4·5)을 chargeMock에 재적용.
class WalletChargeServiceTest {

    private final WalletChargeRepository walletChargeRepository = mock(WalletChargeRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WalletTransactionRepository walletTransactionRepository = mock(WalletTransactionRepository.class);

    private WalletChargeService walletChargeService;

    private UUID memberId;
    private Long amount;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        walletChargeService = new WalletChargeService(walletChargeRepository, walletRepository, walletTransactionRepository);

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
}
