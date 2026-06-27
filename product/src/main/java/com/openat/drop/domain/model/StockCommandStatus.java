package com.openat.drop.domain.model;

public enum StockCommandStatus {
  OK,
  DUPLICATE,
  NOT_OPEN,
  CLOSED,
  LIMIT_EXCEEDED,
  SOLD_OUT,
  NOT_CACHED
}
