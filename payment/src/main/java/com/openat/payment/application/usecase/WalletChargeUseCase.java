package com.openat.payment.application.usecase;

import com.openat.payment.application.dto.ChargeConfirmCommand;
import com.openat.payment.application.dto.ChargePgCommand;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;

public interface WalletChargeUseCase {

    WalletChargeResult chargeMock(ChargeWalletCommand command);

    WalletChargeResult chargePg(ChargePgCommand command);

    // E1 — 충전 PG 승인의 메인 경로(브라우저가 토스 SDK로 받은 paymentKey를 전달해서 호출).
    WalletChargeResult confirmCharge(ChargeConfirmCommand command);
}
