package com.openat.payment.presentation.controller;

import com.openat.payment.application.dto.InternalRefundResult;
import com.openat.payment.application.service.InternalRefundService;
import com.openat.payment.presentation.dto.InternalRefundRequest;
import com.openat.payment.presentation.dto.InternalRefundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// order 주문취소 사가의 환불 진입점(클러스터 내부 DNS 직행, 게이트웨이 미경유). orderId만 받아 기존 환불 파이프라인을 재사용한다.
// REFUND_ACCEPTED는 접수 보장만 — 최종 완료 통지는 기존 refund-completed Kafka 이벤트가 담당(order 사가 무변경).
@Tag(name = "InternalRefunds", description = "order 서비스용 주문 기준 내부 환불")
@RestController
public class InternalRefundController {

    private final InternalRefundService internalRefundService;

    public InternalRefundController(InternalRefundService internalRefundService) {
        this.internalRefundService = internalRefundService;
    }

    @Operation(summary = "주문 기준 환불 요청",
            description = "Idempotency-Key(refund-order-{orderId})로 재시도 안전. 결제 성사분 없으면 NO_PAYMENT, 진행 중이면 PAYMENT_PENDING(409).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "NO_PAYMENT 또는 REFUND_ACCEPTED(접수 보장, 완료는 refund-completed 이벤트)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "PAYMENT_PENDING(결제 진행 중 — 잠시 후 재시도/폴링)")
    })
    @PostMapping("/internal/v1/refunds")
    public ResponseEntity<InternalRefundResponse> refund(
            @Parameter(description = "멱등키(refund-order-{orderId}), 재시도 시 동일 키 재사용", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody InternalRefundRequest request) {
        InternalRefundResult result =
                internalRefundService.refundByOrder(request.orderId(), idempotencyKey);
        HttpStatus status =
                result == InternalRefundResult.PAYMENT_PENDING ? HttpStatus.CONFLICT : HttpStatus.OK;
        return ResponseEntity.status(status).body(new InternalRefundResponse(result.name()));
    }
}
