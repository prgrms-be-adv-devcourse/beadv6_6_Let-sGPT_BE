package com.openat.drop.domain.repository;

import com.openat.drop.domain.model.StockCommandStatus;

public record StockCommandResult(StockCommandStatus status, long remaining) {}
