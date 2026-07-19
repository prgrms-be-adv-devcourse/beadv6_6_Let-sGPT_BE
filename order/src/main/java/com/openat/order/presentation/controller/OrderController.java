package com.openat.order.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.response.PageResponse;
import com.openat.common.web.Locations;
import com.openat.order.application.dto.OrderDetailInfo;
import com.openat.order.application.usecase.OrderUseCase;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.presentation.dto.CreateOrderRequest;
import com.openat.order.presentation.dto.CreateOrderResponse;
import com.openat.order.presentation.dto.InternalOrderValidationResponse;
import com.openat.order.presentation.dto.InternalPurchaseSignalResponse;
import com.openat.order.presentation.dto.OrderCancelResponse;
import com.openat.order.presentation.dto.OrderResponse;
import com.openat.order.presentation.dto.OrderSummaryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Validated
public class OrderController implements OrderApiSpec {

    private final OrderUseCase orderUseCase;
    private final MeterRegistry meterRegistry;

    @Override
    @PostMapping("/api/v1/orders")
    public ResponseEntity<CreateOrderResponse> createOrder(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        CreateOrderResponse response;
        try {
            response = CreateOrderResponse.from(
                    orderUseCase.createOrder(memberId(userContext), request.toCommand()));
        } catch (RuntimeException e) {
            meterRegistry.counter("order.create.result", "result", "fail").increment();
            throw e;
        }
        meterRegistry.counter("order.create.result",
                "result", response.created() ? "created" : "duplicate").increment();
        if (response.created()) {
            return ResponseEntity.created(Locations.fromCurrentRequest(response.orderId()))
                    .body(response);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.LOCATION, Locations.fromCurrentRequest(response.orderId()).toString())
                .body(response);
    }

    @Override
    @GetMapping("/api/v1/orders/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @CurrentUser UserContext userContext,
            @PathVariable UUID orderId
    ) {
        OrderDetailInfo detail = orderUseCase.getMyOrder(memberId(userContext), orderId);
        return ResponseEntity.ok(OrderResponse.from(detail));
    }

    @Override
    @GetMapping("/api/v1/orders")
    public ResponseEntity<PageResponse<OrderSummaryResponse>> getMyOrders(
            @CurrentUser UserContext userContext,
            @RequestParam(required = false) OrderStatus status,
            Pageable pageable
    ) {
        Page<OrderSummaryResponse> page =
                orderUseCase.getMyOrders(memberId(userContext), status, pageable).map(OrderSummaryResponse::from);
        return ResponseEntity.ok(PageResponse.of(page));
    }

    @Override
    @PostMapping("/api/v1/orders/{orderId}/cancel")
    public ResponseEntity<OrderCancelResponse> cancelOrder(
            @CurrentUser UserContext userContext,
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(OrderCancelResponse.from(orderUseCase.cancelOrder(memberId(userContext), orderId)));
    }

    @Override
    @GetMapping("/internal/v1/orders/{orderId}")
    public ResponseEntity<InternalOrderValidationResponse> getOrderForPayment(
            @PathVariable UUID orderId,
            @RequestParam UUID memberId
    ) {
        return ResponseEntity.ok(
                InternalOrderValidationResponse.from(orderUseCase.getPaymentValidationInfo(memberId, orderId)));
    }

    @Override
    @GetMapping("/internal/v1/orders/purchase-signals")
    public ResponseEntity<List<InternalPurchaseSignalResponse>> getPurchaseSignals(
            @RequestParam UUID memberId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(orderUseCase.getPurchaseSignals(memberId, limit).stream()
                .map(InternalPurchaseSignalResponse::from)
                .toList());
    }

    private UUID memberId(UserContext userContext) {
        return UUID.fromString(userContext.userId());
    }
}
