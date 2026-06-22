package com.openat.payment.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.common.response.ApiResponse;
import com.openat.payment.application.dto.PayWithPgCommand;
import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.dto.PgConfirmCommand;
import com.openat.payment.application.usecase.PaymentUseCase;
import com.openat.payment.presentation.dto.PaymentConfirmRequest;
import com.openat.payment.presentation.dto.PaymentRequest;
import com.openat.payment.presentation.dto.PaymentResponse;
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
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentUseCase paymentUseCase;

    public PaymentController(PaymentUseCase paymentUseCase) {
        this.paymentUseCase = paymentUseCase;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> create(
            @RequestHeader("X-User-Id") UUID memberId,
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
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body, HttpStatus.CREATED));
    }

    // A16 — PG 결제 승인의 메인 경로. successUrl=(가) 프론트엔드 페이지 확정에 따라, 프론트가 이 엔드포인트를 호출.
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirm(
            @RequestHeader("X-User-Id") UUID memberId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentConfirmRequest request) {
        PaymentResult result = paymentUseCase.confirmPg(
                new PgConfirmCommand(request.orderId(), memberId, request.amount(), request.paymentKey(), idempotencyKey));
        PaymentResponse body = PaymentResponse.of(result.paymentId(), result.status());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentResponse>> get(@PathVariable UUID id) {
        PaymentResult result = paymentUseCase.getPayment(id);
        PaymentResponse body = PaymentResponse.of(result.paymentId(), result.status());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
