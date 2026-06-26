package com.openat.order.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.error.ErrorResponse;
import com.openat.common.response.PageResponse;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.presentation.dto.CreateOrderRequest;
import com.openat.order.presentation.dto.CreateOrderResponse;
import com.openat.order.presentation.dto.InternalOrderValidationResponse;
import com.openat.order.presentation.dto.OrderCancelResponse;
import com.openat.order.presentation.dto.OrderResponse;
import com.openat.order.presentation.dto.OrderSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Order", description = "주문 API")
@ApiResponses({
        @ApiResponse(
                responseCode = "400",
                description = "입력값 검증 실패",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "서버 내부 오류",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public interface OrderApiSpec {

    @Operation(summary = "주문 생성", description = "드롭 상품 주문을 생성하고 결제 대기 상태로 전환한다.")
    @ApiResponse(
            responseCode = "201",
            description = "생성 성공",
            headers = @Header(name = "Location", description = "생성된 주문 URI"))
    ResponseEntity<CreateOrderResponse> createOrder(
            @CurrentUser UserContext userContext,
            @Valid CreateOrderRequest request);

    @Operation(summary = "주문 조회", description = "로그인 사용자의 주문 단건을 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    ResponseEntity<OrderResponse> getOrder(@CurrentUser UserContext userContext, UUID orderId);

    @Operation(summary = "내 주문 목록 조회", description = "로그인 사용자의 주문 목록을 페이징 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    ResponseEntity<PageResponse<OrderSummaryResponse>> getMyOrders(
            @CurrentUser UserContext userContext,
            @RequestParam(required = false) OrderStatus status,
            @ParameterObject Pageable pageable);

    @Operation(summary = "주문 취소 요청", description = "결제 대기 주문은 CANCELLED, 결제 완료 주문은 CANCEL_REQUESTED로 전환한다.")
    @ApiResponse(responseCode = "200", description = "요청 성공")
    ResponseEntity<OrderCancelResponse> cancelOrder(@CurrentUser UserContext userContext, UUID orderId);

    @Operation(summary = "결제용 주문 조회", description = "결제 도메인이 PG 토큰 발급 전 주문 금액과 상태를 검증하는 내부 API")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    ResponseEntity<InternalOrderValidationResponse> getOrderForPayment(UUID orderId);
}
