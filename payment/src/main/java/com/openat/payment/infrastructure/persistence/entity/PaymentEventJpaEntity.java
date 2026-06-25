package com.openat.payment.infrastructure.persistence.entity;

import com.openat.payment.domain.model.PaymentEvent;
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
@Table(name = "payment_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class PaymentEventJpaEntity {

    @Id
    private UUID id;

    // append-only 이벤트 로그 — 생성 후 절대 수정 안 됨(qna.md Q19 개선 권장사항 적용)
    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PaymentEvent.Type type;

    @Column(nullable = false, updatable = false)
    private Long amount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PaymentEventJpaEntity(UUID id, UUID paymentId, PaymentEvent.Type type, Long amount,
            LocalDateTime createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.type = type;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public static PaymentEventJpaEntity fromDomain(PaymentEvent event) {
        return new PaymentEventJpaEntity(
                event.getId(), event.getPaymentId(), event.getType(), event.getAmount(), event.getCreatedAt());
    }

    public PaymentEvent toDomain() {
        return PaymentEvent.builder()
                .id(id)
                .paymentId(paymentId)
                .type(type)
                .amount(amount)
                .createdAt(createdAt)
                .build();
    }
}
