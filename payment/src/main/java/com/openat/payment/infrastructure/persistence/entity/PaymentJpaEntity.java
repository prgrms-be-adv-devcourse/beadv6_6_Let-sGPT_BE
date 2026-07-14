package com.openat.payment.infrastructure.persistence.entity;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PgReconStatus;
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
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class PaymentJpaEntity {

    @Id
    private UUID id;

    // 한 주문에 여러 시도(행)가 있을 수 있어 not unique — "성공 1건" 보장은 부분 유니크 인덱스(DB)가 담당
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    // 생성 시점엔 비워두고 order_completed 이벤트로 사후 채움(B2)
    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Payment.Method method;

    @Column(name = "pg_provider", length = 20)
    private String pgProvider;

    // method=PG일 때만, 민감정보 — AES-GCM 암호화 후 저장(암호문은 평문보다 길어서 length 여유 둠)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "pg_payment_key", length = 500)
    private String pgPaymentKey;

    // pgPaymentKey는 암호화(비결정적 IV)되어 등호 조회가 불가능 — 웹훅 매칭은 이 평문 해시(결정적)로 수행
    @Column(name = "pg_payment_key_hash", length = 64)
    private String pgPaymentKeyHash;

    // 웹훅 중복 수신 판단 기준
    @Column(name = "pg_tx_id", length = 100)
    private String pgTxId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Payment.Status status;

    // 누적 환불 금액(의도적 비정규화) — 갱신은 조건부 UPDATE(refundedAmount+? <= amount)
    @Column(name = "refunded_amount", nullable = false)
    private Long refundedAmount;

    // PG 대사(WS-0) — 정산 대사 일별 API는 이 값이 MATCHED인 행만 노출한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "pg_recon_status", nullable = false, length = 20)
    private PgReconStatus pgReconStatus;

    @Column(name = "pg_reconciled_at")
    private LocalDateTime pgReconciledAt;

    // 멱등키는 시도 단위로 발급(orderId 단독 아님)
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    // 동일 idempotencyKey로 바디가 다른 요청이 재전송되면 충돌로 판단(#7)
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    // APPROVED 전이 시점 1회 기록(updatedAt과 분리)
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private PaymentJpaEntity(UUID id, UUID orderId, UUID memberId, UUID sellerId, UUID productId, Long amount,
            Payment.Method method, String pgProvider, String pgPaymentKey, String pgPaymentKeyHash, String pgTxId,
            Payment.Status status, Long refundedAmount, PgReconStatus pgReconStatus, LocalDateTime pgReconciledAt,
            String idempotencyKey, String requestHash,
            LocalDateTime approvedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.memberId = memberId;
        this.sellerId = sellerId;
        this.productId = productId;
        this.amount = amount;
        this.method = method;
        this.pgProvider = pgProvider;
        this.pgPaymentKey = pgPaymentKey;
        this.pgPaymentKeyHash = pgPaymentKeyHash;
        this.pgTxId = pgTxId;
        this.status = status;
        this.refundedAmount = refundedAmount;
        this.pgReconStatus = pgReconStatus;
        this.pgReconciledAt = pgReconciledAt;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.approvedAt = approvedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PaymentJpaEntity fromDomain(Payment payment) {
        return new PaymentJpaEntity(
                payment.getId(), payment.getOrderId(), payment.getMemberId(), payment.getSellerId(),
                payment.getProductId(), payment.getAmount(), payment.getMethod(), payment.getPgProvider(),
                payment.getPgPaymentKey(), payment.getPgPaymentKeyHash(), payment.getPgTxId(), payment.getStatus(),
                payment.getRefundedAmount(), payment.getPgReconStatus(), payment.getPgReconciledAt(),
                payment.getIdempotencyKey(), payment.getRequestHash(),
                payment.getApprovedAt(), payment.getCreatedAt(), payment.getUpdatedAt());
    }

    public Payment toDomain() {
        return Payment.builder()
                .id(id)
                .orderId(orderId)
                .memberId(memberId)
                .sellerId(sellerId)
                .productId(productId)
                .amount(amount)
                .method(method)
                .pgProvider(pgProvider)
                .pgPaymentKey(pgPaymentKey)
                .pgPaymentKeyHash(pgPaymentKeyHash)
                .pgTxId(pgTxId)
                .status(status)
                .refundedAmount(refundedAmount)
                .pgReconStatus(pgReconStatus)
                .pgReconciledAt(pgReconciledAt)
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .approvedAt(approvedAt)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
