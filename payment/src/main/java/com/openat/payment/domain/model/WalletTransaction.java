package com.openat.payment.domain.model;

import com.openat.payment.domain.model.support.UuidV7Generator;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 순수 도메인 모델(JPA 비의존) — 영속화는 infrastructure/persistence/entity/WalletTransactionJpaEntity가 담당.
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WalletTransaction {

  @Builder.Default private UUID id = UuidV7Generator.generate();

  private UUID walletId;

  private Type type;

  private Long amount;

  private Long balanceAfter;

  private String idempotencyKey;

  private LocalDateTime createdAt;

  // balanceAfter는 항상 호출 측이 UPDATE 후 재조회한 값을 넘긴다(§4.2 결함 수정, D3 — row lock으로 정확성 보장).
  public static WalletTransaction deductOf(
      UUID walletId, Long amount, Long balanceAfter, String idempotencyKey) {
    return of(Type.DEDUCT, walletId, amount, balanceAfter, idempotencyKey);
  }

  public static WalletTransaction chargeOf(
      UUID walletId, Long amount, Long balanceAfter, String idempotencyKey) {
    return of(Type.CHARGE, walletId, amount, balanceAfter, idempotencyKey);
  }

  public static WalletTransaction refundOf(
      UUID walletId, Long amount, Long balanceAfter, String idempotencyKey) {
    return of(Type.REFUND, walletId, amount, balanceAfter, idempotencyKey);
  }

  private static WalletTransaction of(
      Type type, UUID walletId, Long amount, Long balanceAfter, String idempotencyKey) {
    return WalletTransaction.builder()
        .walletId(walletId)
        .type(type)
        .amount(amount)
        .balanceAfter(balanceAfter)
        .idempotencyKey(idempotencyKey)
        .createdAt(LocalDateTime.now())
        .build();
  }

  public enum Type {
    CHARGE,
    DEDUCT,
    REFUND
  }
}
