package com.openat.order.presentation.controller;

import com.openat.order.application.usecase.OrderQueryUseCase;
import com.openat.order.presentation.dto.OrderValidationResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderQueryUseCase orderQueryUseCase;

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderValidationResponse> validateForPayment(@PathVariable UUID orderId) {
        return ResponseEntity.ok(OrderValidationResponse.from(orderQueryUseCase.validateForPayment(orderId)));
    }
}
