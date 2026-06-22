package com.openat.payment.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.common.response.ApiResponse;
import com.openat.payment.application.dto.ChargeConfirmCommand;
import com.openat.payment.application.dto.ChargePgCommand;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;
import com.openat.payment.application.usecase.WalletChargeUseCase;
import com.openat.payment.presentation.dto.WalletChargeConfirmRequest;
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
        WalletChargeResult result = switch (request.method()) {
            case "MOCK" -> walletChargeUseCase.chargeMock(
                    new ChargeWalletCommand(memberId, request.amount(), idempotencyKey));
            case "PG" -> walletChargeUseCase.chargePg(
                    new ChargePgCommand(memberId, request.amount(), idempotencyKey));
            default -> throw new BusinessException(CommonErrorCode.INVALID_INPUT, "지원하지 않는 충전수단: " + request.method());
        };

        WalletChargeResponse body = new WalletChargeResponse(result.chargeId(), result.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body, HttpStatus.CREATED));
    }

    // E1 — 충전 PG 승인의 메인 경로. 프론트가 결제와 동일한 모양으로 이 엔드포인트를 호출.
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<WalletChargeResponse>> confirm(
            @RequestHeader("X-User-Id") UUID memberId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody WalletChargeConfirmRequest request) {
        WalletChargeResult result = walletChargeUseCase.confirmCharge(new ChargeConfirmCommand(
                request.chargeId(), memberId, request.amount(), request.paymentKey(), idempotencyKey));
        WalletChargeResponse body = new WalletChargeResponse(result.chargeId(), result.status());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
