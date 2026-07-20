package com.openat.order.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.error.ErrorResponse;
import com.openat.common.response.PageResponse;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.presentation.dto.CreateOrderRequest;
import com.openat.order.presentation.dto.CreateOrderResponse;
import com.openat.order.presentation.dto.InternalOrderValidationResponse;
import com.openat.order.presentation.dto.InternalPurchaseSignalResponse;
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
import jakarta.validation.constraints.Min;
import java.util.List;
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
      @CurrentUser UserContext userContext, @Valid CreateOrderRequest request);

  @Operation(summary = "주문 조회", description = "로그인 사용자의 주문 단건을 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  ResponseEntity<OrderResponse> getOrder(@CurrentUser UserContext userContext, UUID orderId);

  @Operation(summary = "내 주문 목록 조회", description = "로그인 사용자의 주문 목록을 페이징 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  ResponseEntity<PageResponse<OrderSummaryResponse>> getMyOrders(
      @CurrentUser UserContext userContext,
      @RequestParam(required = false) OrderStatus status,
      @ParameterObject Pageable pageable);

  @Operation(summary = "주문 취소", description = "결제 대기 주문만 취소한다. 결제 완료 주문은 환불 요청 API를 사용한다.")
  @ApiResponse(responseCode = "200", description = "요청 성공")
  ResponseEntity<OrderCancelResponse> cancelOrder(
      @CurrentUser UserContext userContext, UUID orderId);

  @Operation(summary = "환불 요청", description = "결제 완료 주문을 CANCEL_REQUESTED로 전환하고 payment에 환불을 요청한다.")
  @ApiResponse(responseCode = "200", description = "취소 접수 성공")
  ResponseEntity<OrderCancelResponse> requestRefund(
      @CurrentUser UserContext userContext, UUID orderId);

  @Operation(
      summary = "환불 재트리거",
      description = "REFUND_FAILED 또는 CANCEL_REQUESTED 주문의 환불을 회차 키로 재요청한다.")
  ResponseEntity<OrderCancelResponse> retryRefund(UUID orderId);

  @Operation(
      summary = "환불 수동 확정",
      description = "REFUND_FAILED 또는 CANCELLED 주문을 운영자가 REFUNDED로 정정한다.")
  ResponseEntity<OrderCancelResponse> confirmRefund(UUID orderId);

  @Operation(
      summary = "재고 롤백 재트리거",
      description = "STOCK_ROLLBACK_FAILED 및 COMPENSATING 주문의 재고 롤백을 재시도한다.")
  ResponseEntity<OrderCancelResponse> retryStockRollback(UUID orderId);

  @Operation(
      summary = "결제용 주문 조회",
      description =
          "결제 도메인이 PG 토큰 발급 전 주문 금액과 상태를 검증하는 내부 API. " + "넘어온 memberId로 소유자 검증(불일치 시 거부)")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  ResponseEntity<InternalOrderValidationResponse> getOrderForPayment(UUID orderId, UUID memberId);

  @Operation(summary = "구매 신호 조회", description = "회원의 결제 완료 주문을 상품별로 집계하는 내부 API")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  ResponseEntity<List<InternalPurchaseSignalResponse>> getPurchaseSignals(
      UUID memberId, @Min(1) int limit);
}
