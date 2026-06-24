package com.openat.payment.domain.model;

import com.openat.payment.domain.model.support.UuidV7Generator;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 순수 도메인 모델(JPA 비의존) — 영속화는 infrastructure/persistence/entity/WalletJpaEntity가 담당.
// 낙관적 락(version)은 순수 영속화 관심사라 도메인에는 노출하지 않음.
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Wallet {

    @Builder.Default
    private UUID id = UuidV7Generator.generate();

    private UUID memberId;

    private Long balance;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
