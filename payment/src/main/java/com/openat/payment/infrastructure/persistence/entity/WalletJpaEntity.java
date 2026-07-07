package com.openat.payment.infrastructure.persistence.entity;

import com.openat.payment.domain.model.Wallet;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class WalletJpaEntity {

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false, unique = true)
    private UUID memberId;

    @Column(nullable = false)
    private Long balance;

    // 순수 영속화 관심사(보조 동시성 방어, qna.md Q20) — 도메인에는 노출 안 함
    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private WalletJpaEntity(UUID id, UUID memberId, Long balance, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.memberId = memberId;
        this.balance = balance;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static WalletJpaEntity fromDomain(Wallet wallet) {
        return new WalletJpaEntity(
                wallet.getId(), wallet.getMemberId(), wallet.getBalance(),
                wallet.getCreatedAt(), wallet.getUpdatedAt());
    }

    public Wallet toDomain() {
        return Wallet.builder()
                .id(id)
                .memberId(memberId)
                .balance(balance)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
