package com.openat.drop.presentation.controller;

import com.openat.drop.application.usecase.DropStockUseCase;
import com.openat.drop.infrastructure.metrics.DropStockMetrics;
import com.openat.drop.presentation.dto.StockChangeRequest;
import com.openat.drop.presentation.dto.StockChangeResponse;
import com.openat.support.web.InternalApi;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/drops")
@InternalApi
@RequiredArgsConstructor
public class InternalDropStockController {

  private final DropStockUseCase dropStockUseCase;
  private final DropStockMetrics dropStockMetrics;

  @PostMapping("/{dropId}/stock-deductions")
  public ResponseEntity<StockChangeResponse> deduct(
      @PathVariable UUID dropId, @Valid @RequestBody StockChangeRequest request) {
    long remaining = dropStockUseCase.deduct(request.toCommand(dropId));
    dropStockMetrics.record(dropId, remaining);
    return ResponseEntity.ok(new StockChangeResponse(remaining));
  }

  @PostMapping("/{dropId}/stock-rollbacks")
  public ResponseEntity<StockChangeResponse> rollback(
      @PathVariable UUID dropId, @Valid @RequestBody StockChangeRequest request) {
    Optional<Long> remaining = dropStockUseCase.rollback(request.toCommand(dropId));
    if (remaining.isEmpty()) {
      return ResponseEntity.noContent().build();
    }
    dropStockMetrics.record(dropId, remaining.get());
    return ResponseEntity.ok(new StockChangeResponse(remaining.get()));
  }
}
