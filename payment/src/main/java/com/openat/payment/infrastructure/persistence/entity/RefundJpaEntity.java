package com.openat.payment.infrastructure.persistence.entity;

import com.openat.payment.domain.model.Refund;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// orderId/sellerId/buyerId/method 등은 중복 저장하지 않음 — payment_id로 Payment를 조인해서 그 시점에 조립(A6)
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class RefundJpaEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Refund.Status status;

    @Column(length = 255)
    private String reason;

    // 민감정보 — AES-GCM 암호화 후 저장(암호문은 평문보다 길어서 length 여유 둠)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pg_refund_key", length = 500)
    private String pgRefundKey;

    // PG 환불 호출에도 동일 키 부착(#12)
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private RefundJpaEntity(UUID id, UUID paymentId, Long amount, Refund.Status status, String reason,
            String pgRefundKey, String idempotencyKey, LocalDateTime completedAt, LocalDateTime createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.pgRefundKey = pgRefundKey;
        this.idempotencyKey = idempotencyKey;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
    }

    public static RefundJpaEntity fromDomain(Refund refund) {
        return new RefundJpaEntity(
                refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getStatus(), refund.getReason(),
                refund.getPgRefundKey(), refund.getIdempotencyKey(), refund.getCompletedAt(), refund.getCreatedAt());
    }

    public Refund toDomain() {
        return Refund.builder()
                .id(id)
                .paymentId(paymentId)
                .amount(amount)
                .status(status)
                .reason(reason)
                .pgRefundKey(pgRefundKey)
                .idempotencyKey(idempotencyKey)
                .completedAt(completedAt)
                .createdAt(createdAt)
                .build();
    }
}
