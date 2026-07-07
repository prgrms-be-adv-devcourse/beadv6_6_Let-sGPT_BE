package com.openat.drop.application.service;

import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.model.StockHistory;
import com.openat.drop.domain.repository.StockHistoryRepository;
import com.openat.drop.domain.repository.StockMutation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class StockHistoryRecorder {

  private final StockHistoryRepository stockHistoryRepository;

  public void record(StockMutation mutation, StockChangeType changeType) {
    StockHistory newStockHistory =
        StockHistory.record()
            .dropId(mutation.dropId())
            .orderId(mutation.orderId())
            .buyerId(mutation.buyerId())
            .changeType(changeType)
            .quantity(mutation.quantity())
            .build();
    stockHistoryRepository.save(newStockHistory);
  }
}
