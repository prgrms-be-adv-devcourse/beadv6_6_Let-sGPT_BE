package com.openat.payment.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.common.response.ApiResponse;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;
import com.openat.payment.application.usecase.WalletChargeUseCase;
import com.openat.payment.presentation.dto.WalletChargeRequest;
import com.openat.payment.presentation.dto.WalletChargeResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet/charge")
public class WalletChargeController {

    private final WalletChargeUseCase walletChargeUseCase;

    public WalletChargeController(WalletChargeUseCase walletChargeUseCase) {
        this.walletChargeUseCase = walletChargeUseCase;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WalletChargeResponse>> charge(
            @RequestHeader("X-User-Id") UUID memberId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody WalletChargeRequest request) {
        if (!"MOCK".equals(request.method())) {
            // PG 충전은 Day5 범위 — 지금은 MOCK만 지원.
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "PG 충전은 아직 지원하지 않습니다(Day5)");
        }

        WalletChargeResult result =
                walletChargeUseCase.chargeMock(new ChargeWalletCommand(memberId, request.amount(), idempotencyKey));

        WalletChargeResponse body = new WalletChargeResponse(result.chargeId(), result.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body, HttpStatus.CREATED));
    }
}
