package com.openat.drop.presentation.controller;

import com.openat.drop.application.usecase.DropStockUseCase;
import com.openat.drop.presentation.dto.StockChangeRequest;
import com.openat.drop.presentation.dto.StockChangeResponse;
import com.openat.support.web.InternalApi;
import jakarta.validation.Valid;
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

  @PostMapping("/{dropId}/stock-deductions")
  public ResponseEntity<StockChangeResponse> deduct(
      @PathVariable UUID dropId, @Valid @RequestBody StockChangeRequest request) {
    long remaining = dropStockUseCase.deduct(request.toCommand(dropId));
    return ResponseEntity.ok(new StockChangeResponse(remaining));
  }
}
