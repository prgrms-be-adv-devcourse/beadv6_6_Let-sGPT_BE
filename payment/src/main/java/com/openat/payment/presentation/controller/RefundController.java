package com.openat.payment.presentation.controller;

import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.usecase.RefundUseCase;
import com.openat.payment.presentation.dto.RefundHistoryResponse;
import com.openat.payment.presentation.dto.RefundRequest;
import com.openat.payment.presentation.dto.RefundResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// X-User-Id는 게이트웨이/회원 서비스가 JWT 검증 후 주입한다는 전제로 그대로 신뢰(B1 확정 — PaymentController와 동일).
@Tag(name = "Refunds", description = "결제 환불 요청·조회·이력")
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {

    private final RefundUseCase refundUseCase;

    public RefundController(RefundUseCase refundUseCase) {
        this.refundUseCase = refundUseCase;
    }

    @Operation(summary = "환불 요청", description = "WALLET 결제는 지갑으로 즉시 환불, PG 결제는 토스 결제취소를 동기 호출한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "요청 처리 완료(COMPLETE/FAILED/PENDING 중 하나, status로 구분)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN(본인 결제가 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND(대상 결제 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "EXCEED_REFUNDABLE_AMOUNT(환불 가능액 초과) / IDEMPOTENCY_KEY_CONFLICT")
    })
    @PostMapping
    public ResponseEntity<RefundResponse> request(
            @Parameter(description = "인증된 회원 ID(게이트웨이 주입)", required = true)
            @RequestHeader("X-User-Id") UUID memberId,
            @Parameter(description = "멱등키, 재시도 시 동일 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RefundRequest request) {
        RefundResult result = refundUseCase.requestRefund(
                new RefundCommand(request.paymentId(), memberId, request.amount(), request.reason(), idempotencyKey));
        RefundResponse body = toResponse(result);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "환불 단건 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND")
    })
    @GetMapping("/{id}")
    public ResponseEntity<RefundResponse> get(
            @Parameter(description = "환불 ID") @PathVariable UUID id) {
        RefundResult result = refundUseCase.getRefund(id);
        return ResponseEntity.ok(toResponse(result));
    }

    @Operation(summary = "내 환불 이력 조회(페이징)")
    @GetMapping("/histories")
    public ResponseEntity<RefundHistoryResponse> histories(
            @Parameter(description = "인증된 회원 ID(게이트웨이 주입)", required = true)
            @RequestHeader("X-User-Id") UUID memberId,
            @Parameter(description = "페이지 번호(0-base)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {
        RefundHistoryResult result = refundUseCase.getRefundHistories(memberId, page, size);
        RefundHistoryResponse body = new RefundHistoryResponse(
                result.content().stream().map(RefundController::toResponse).toList(), result.totalPages());
        return ResponseEntity.ok(body);
    }

    private static RefundResponse toResponse(RefundResult result) {
        return new RefundResponse(result.refundId(), result.paymentId(), result.amount(), result.status());
    }
}
