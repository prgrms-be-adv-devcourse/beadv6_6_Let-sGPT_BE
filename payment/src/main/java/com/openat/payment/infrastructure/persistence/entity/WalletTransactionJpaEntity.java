package com.openat.payment.infrastructure.persistence.entity;

import com.openat.payment.domain.model.WalletTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "wallet_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class WalletTransactionJpaEntity {

    @Id
    private UUID id;

    // append-only 원장 — 생성 후 절대 수정 안 됨(qna.md Q19 개선 권장사항 적용)
    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private WalletTransaction.Type type;

    @Column(nullable = false, updatable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private Long balanceAfter;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100, updatable = false)
    private String idempotencyKey;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private WalletTransactionJpaEntity(UUID id, UUID walletId, WalletTransaction.Type type, Long amount,
            Long balanceAfter, String idempotencyKey, LocalDateTime createdAt) {
        this.id = id;
        this.walletId = walletId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public static WalletTransactionJpaEntity fromDomain(WalletTransaction tx) {
        return new WalletTransactionJpaEntity(
                tx.getId(), tx.getWalletId(), tx.getType(), tx.getAmount(),
                tx.getBalanceAfter(), tx.getIdempotencyKey(), tx.getCreatedAt());
    }

    public WalletTransaction toDomain() {
        return WalletTransaction.builder()
                .id(id)
                .walletId(walletId)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .idempotencyKey(idempotencyKey)
                .createdAt(createdAt)
                .build();
    }
}
