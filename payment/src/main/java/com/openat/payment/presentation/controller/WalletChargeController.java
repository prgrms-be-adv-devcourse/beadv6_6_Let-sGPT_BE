package com.openat.payment.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.ChargeConfirmCommand;
import com.openat.payment.application.dto.ChargePgCommand;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;
import com.openat.payment.application.usecase.WalletChargeUseCase;
import com.openat.payment.presentation.dto.WalletChargeConfirmRequest;
import com.openat.payment.presentation.dto.WalletChargeRequest;
import com.openat.payment.presentation.dto.WalletChargeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallet Charge", description = "지갑 충전(MOCK/PG) 생성·승인")
@RestController
@RequestMapping("/api/v1/wallet/charge")
public class WalletChargeController {

    private final WalletChargeUseCase walletChargeUseCase;

    public WalletChargeController(WalletChargeUseCase walletChargeUseCase) {
        this.walletChargeUseCase = walletChargeUseCase;
    }

    // TODO(fe-api): 지갑 잔액 조회 API 미구현. FE 마이페이지(screens/10) 잔액 표기에 필요.
    //   GET /api/v1/wallet -> { balance: long }(인증 회원 기준). (현재 FE 는 MSW provisional 로 구동)

    @Operation(summary = "지갑 충전 생성", description = "MOCK은 즉시 승인, PG는 PENDING row만 생성하며 승인은 /confirm에서 처리한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT(지원하지 않는 충전수단)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_CONFLICT")
    })
    @PostMapping
    public ResponseEntity<WalletChargeResponse> charge(
            @Parameter(description = "인증된 회원 정보(게이트웨이 주입)", required = true)
            @CurrentUser UserContext userContext,
            @Parameter(description = "멱등키, 재시도 시 동일 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody WalletChargeRequest request) {
        UUID memberId = UUID.fromString(userContext.userId());
        WalletChargeResult result = switch (request.method()) {
            case "MOCK" -> walletChargeUseCase.chargeMock(
                    new ChargeWalletCommand(memberId, request.amount(), idempotencyKey));
            case "PG" -> walletChargeUseCase.chargePg(
                    new ChargePgCommand(memberId, request.amount(), idempotencyKey));
            default -> throw new BusinessException(CommonErrorCode.INVALID_INPUT, "지원하지 않는 충전수단: " + request.method());
        };

        WalletChargeResponse body = new WalletChargeResponse(result.chargeId(), result.status());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // E1 — 충전 PG 승인의 메인 경로. 프론트가 결제와 동일한 모양으로 이 엔드포인트를 호출.
    @Operation(summary = "충전 PG 승인", description = "브라우저의 토스 SDK 호출 후 successUrl로 전달받은 paymentKey로 PG 승인을 확정한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 처리 완료(승인/거절 모두 200, status로 구분)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND(대상 충전 없음)")
    })
    @PostMapping("/confirm")
    public ResponseEntity<WalletChargeResponse> confirm(
            @Parameter(description = "인증된 회원 정보(게이트웨이 주입)", required = true)
            @CurrentUser UserContext userContext,
            @Parameter(description = "멱등키, 재시도 시 동일 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody WalletChargeConfirmRequest request) {
        UUID memberId = UUID.fromString(userContext.userId());
        WalletChargeResult result = walletChargeUseCase.confirmCharge(new ChargeConfirmCommand(
                request.chargeId(), memberId, request.amount(), request.paymentKey(), idempotencyKey));
        WalletChargeResponse body = new WalletChargeResponse(result.chargeId(), result.status());
        return ResponseEntity.ok(body);
    }
}
