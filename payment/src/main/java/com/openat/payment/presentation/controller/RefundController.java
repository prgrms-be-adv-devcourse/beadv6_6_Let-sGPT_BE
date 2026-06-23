package com.openat.payment.presentation.controller;

import com.openat.common.response.ApiResponse;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.usecase.RefundUseCase;
import com.openat.payment.presentation.dto.RefundHistoryResponse;
import com.openat.payment.presentation.dto.RefundRequest;
import com.openat.payment.presentation.dto.RefundResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// X-User-Id는 게이트웨이/회원 서비스가 JWT 검증 후 주입한다는 전제로 그대로 신뢰(B1 확정 — PaymentController와 동일).
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {

    private final RefundUseCase refundUseCase;

    public RefundController(RefundUseCase refundUseCase) {
        this.refundUseCase = refundUseCase;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RefundResponse>> request(
            @RequestHeader("X-User-Id") UUID memberId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RefundRequest request) {
        RefundResult result = refundUseCase.requestRefund(
                new RefundCommand(request.paymentId(), memberId, request.amount(), request.reason(), idempotencyKey));
        RefundResponse body = toResponse(result);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body, HttpStatus.CREATED));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RefundResponse>> get(@PathVariable UUID id) {
        RefundResult result = refundUseCase.getRefund(id);
        return ResponseEntity.ok(ApiResponse.ok(toResponse(result)));
    }

    @GetMapping("/histories")
    public ResponseEntity<ApiResponse<RefundHistoryResponse>> histories(
            @RequestHeader("X-User-Id") UUID memberId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        RefundHistoryResult result = refundUseCase.getRefundHistories(memberId, page, size);
        RefundHistoryResponse body = new RefundHistoryResponse(
                result.content().stream().map(RefundController::toResponse).toList(), result.totalPages());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    private static RefundResponse toResponse(RefundResult result) {
        return new RefundResponse(result.refundId(), result.paymentId(), result.amount(), result.status());
    }
}
