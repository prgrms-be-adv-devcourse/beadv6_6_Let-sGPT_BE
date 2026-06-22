package com.openat.payment.application.usecase;

import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;

public interface WalletChargeUseCase {

    WalletChargeResult chargeMock(ChargeWalletCommand command);
}
