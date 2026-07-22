package com.openat.payment.presentation.controller;

import com.openat.payment.application.dto.InternalPaymentStatusResult;
import com.openat.payment.application.service.InternalPaymentQueryService;
import com.openat.payment.presentation.dto.InternalPaymentStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// order → payment 내부 조회 API(클러스터 내부 DNS 직행, 게이트웨이 미경유). order의 TTL 만료 후 결제 결과 확인용 pull 경로.
// 외부에서의 internal 경로 진입은 게이트웨이 ADMIN 게이트가 차단한다.
@Tag(name = "InternalPayments", description = "order 서비스용 내부 결제 상태 조회")
@RestController
public class InternalPaymentController {

    private final InternalPaymentQueryService internalPaymentQueryService;

    public InternalPaymentController(InternalPaymentQueryService internalPaymentQueryService) {
        this.internalPaymentQueryService = internalPaymentQueryService;
    }

    @Operation(summary = "주문 기준 결제 상태 조회",
            description = "결제 성사분(APPROVED/PARTIALLY_REFUNDED/REFUNDED) 우선, 없으면 최신 1건. 없으면 404.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "NOT_FOUND(해당 주문의 결제 없음)")
    })
    @GetMapping("/internal/v1/payments")
    public ResponseEntity<InternalPaymentStatusResponse> getByOrderId(
            @Parameter(description = "주문 ID", required = true) @RequestParam UUID orderId) {
        InternalPaymentStatusResult result = internalPaymentQueryService.getByOrderId(orderId);
        InternalPaymentStatusResponse body =
                new InternalPaymentStatusResponse(result.paymentId(), result.status(), result.amount());
        return ResponseEntity.ok(body);
    }
}
