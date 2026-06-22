package com.openat.drop.domain.model;

public enum DropStatus {
  SCHEDULED, // 오픈 예정
  OPEN, // 오픈, 구매 가능
  CLOSE, // 종료
  SOLD_OUT // 매진
}
