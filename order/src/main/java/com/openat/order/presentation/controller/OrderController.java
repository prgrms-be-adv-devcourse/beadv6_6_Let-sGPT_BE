package com.openat.order.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.response.PageResponse;
import com.openat.common.web.Locations;
import com.openat.order.application.usecase.OrderCommandUseCase;
import com.openat.order.application.usecase.OrderQueryUseCase;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.presentation.dto.CancelOrderResponse;
import com.openat.order.presentation.dto.CreateOrderRequest;
import com.openat.order.presentation.dto.CreateOrderResponse;
import com.openat.order.presentation.dto.OrderResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCommandUseCase orderCommandUseCase;
    private final OrderQueryUseCase orderQueryUseCase;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create(
            @CurrentUser UserContext currentUser,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResponse response = CreateOrderResponse.from(
                orderCommandUseCase.create(request.toCommand(memberId(currentUser))));
        return ResponseEntity.created(Locations.fromCurrentRequest(response.orderId())).body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> get(
            @CurrentUser UserContext currentUser,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(OrderResponse.from(orderQueryUseCase.get(memberId(currentUser), orderId)));
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrderResponse>> getMyOrders(
            @CurrentUser UserContext currentUser,
            @RequestParam(required = false) OrderStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(PageResponse.of(orderQueryUseCase
                .getMyOrders(memberId(currentUser), status, pageable)
                .map(OrderResponse::from)));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CancelOrderResponse> cancel(
            @CurrentUser UserContext currentUser,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(CancelOrderResponse.from(
                orderCommandUseCase.cancel(memberId(currentUser), orderId)));
    }

    private UUID memberId(UserContext currentUser) {
        return UUID.fromString(currentUser.userId());
    }
}
