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

    @Builder.Default
    private UUID id = UuidV7Generator.generate();

    private UUID walletId;

    private Type type;

    private Long amount;

    private Long balanceAfter;

    private String idempotencyKey;

    private LocalDateTime createdAt;

    public enum Type {
        CHARGE, DEDUCT, REFUND
    }
}
