package com.openat.payment.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.PayWithPgCommand;
import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.dto.PgConfirmCommand;
import com.openat.payment.application.usecase.PaymentUseCase;
import com.openat.payment.presentation.dto.PaymentConfirmRequest;
import com.openat.payment.presentation.dto.PaymentRequest;
import com.openat.payment.presentation.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// X-User-Id는 게이트웨이/회원 서비스가 JWT 검증 후 주입한다는 전제로 그대로 신뢰(B1 확정 — 별도 JWT 검증 없음).
@Tag(name = "Payments", description = "WALLET/PG 결제 생성·승인·조회")
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentUseCase paymentUseCase;

    public PaymentController(PaymentUseCase paymentUseCase) {
        this.paymentUseCase = paymentUseCase;
    }

    @Operation(summary = "결제 생성", description = "WALLET은 즉시 승인하고 지갑에서 차감, PG는 PENDING row만 생성하며 승인은 /confirm에서 처리한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "INVALID_INPUT(지원하지 않는 결제수단) / ORDER_VALIDATION_FAILED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "INSUFFICIENT_BALANCE(WALLET 잔액부족) / IDEMPOTENCY_KEY_CONFLICT")
    })
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @Parameter(description = "인증된 회원 ID(게이트웨이 주입)", required = true)
            @RequestHeader("X-User-Id") UUID memberId,
            @Parameter(description = "멱등키, 재시도 시 동일 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request) {
        PaymentResult result = switch (request.method()) {
            case "WALLET" -> paymentUseCase.payWithWallet(
                    new PayWithWalletCommand(request.orderId(), memberId, request.amount(), idempotencyKey));
            case "PG" -> paymentUseCase.payWithPg(
                    new PayWithPgCommand(request.orderId(), memberId, request.amount(), idempotencyKey));
            default -> throw new BusinessException(CommonErrorCode.INVALID_INPUT, "지원하지 않는 결제수단: " + request.method());
        };

        PaymentResponse body = new PaymentResponse(result.paymentId(), result.status(), result.pgPaymentKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // A16 — PG 결제 승인의 메인 경로. successUrl=(가) 프론트엔드 페이지 확정에 따라, 프론트가 이 엔드포인트를 호출.
    @Operation(summary = "PG 결제 승인", description = "브라우저의 토스 SDK 호출 후 successUrl로 전달받은 paymentKey로 PG 승인을 확정한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 처리 완료(승인/거절 모두 200, status로 구분)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ORDER_VALIDATION_FAILED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND(대상 결제 없음)")
    })
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            @Parameter(description = "인증된 회원 ID(게이트웨이 주입)", required = true)
            @RequestHeader("X-User-Id") UUID memberId,
            @Parameter(description = "멱등키, 재시도 시 동일 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentConfirmRequest request) {
        PaymentResult result = paymentUseCase.confirmPg(
                new PgConfirmCommand(request.orderId(), memberId, request.amount(), request.paymentKey(), idempotencyKey));
        PaymentResponse body = PaymentResponse.of(result.paymentId(), result.status());
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "결제 단건 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND")
    })
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> get(
            @Parameter(description = "결제 ID") @PathVariable UUID id) {
        PaymentResult result = paymentUseCase.getPayment(id);
        PaymentResponse body = PaymentResponse.of(result.paymentId(), result.status());
        return ResponseEntity.ok(body);
    }
}
