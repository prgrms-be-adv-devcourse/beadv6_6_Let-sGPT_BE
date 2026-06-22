package com.openat.payment.infrastructure.persistence.entity;

import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.infrastructure.persistence.converter.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "wallet_charges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class WalletChargeJpaEntity {

    @Id
    private UUID id;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletCharge.Method method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WalletCharge.Status status;

    // method=PG일 때만 채워짐(PG 토큰발급 응답 후), 민감정보 — AES-GCM 암호화(암호문은 평문보다 길어서 length 여유 둠)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pg_payment_key", length = 500)
    private String pgPaymentKey;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    // 동일 idempotencyKey로 바디(amount/method)가 다른 요청이 재전송되면 충돌로 판단(#7)
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private WalletChargeJpaEntity(UUID id, UUID memberId, Long amount, WalletCharge.Method method,
            WalletCharge.Status status, String pgPaymentKey, String idempotencyKey, String requestHash,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.memberId = memberId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.pgPaymentKey = pgPaymentKey;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static WalletChargeJpaEntity fromDomain(WalletCharge charge) {
        return new WalletChargeJpaEntity(
                charge.getId(), charge.getMemberId(), charge.getAmount(), charge.getMethod(),
                charge.getStatus(), charge.getPgPaymentKey(), charge.getIdempotencyKey(), charge.getRequestHash(),
                charge.getCreatedAt(), charge.getUpdatedAt());
    }

    public WalletCharge toDomain() {
        return WalletCharge.builder()
                .id(id)
                .memberId(memberId)
                .amount(amount)
                .method(method)
                .status(status)
                .pgPaymentKey(pgPaymentKey)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
