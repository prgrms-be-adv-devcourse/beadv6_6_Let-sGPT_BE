package com.openat.payment.domain.model;

import com.openat.payment.domain.model.support.UuidV7Generator;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 순수 도메인 모델(JPA 비의존) — 영속화는 infrastructure/persistence/entity/RefundJpaEntity가 담당.
// orderId/sellerId/buyerId/method 등은 중복 저장하지 않음 — paymentId로 Payment를 조인해서 그 시점에 조립(A6)
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Refund {

    @Builder.Default
    private UUID id = UuidV7Generator.generate();

    private UUID paymentId;

    private Long amount;

    private Status status;

    private String reason;

    private String pgRefundKey;

    // PG 환불 호출에도 동일 키 부착(#12)
    private String idempotencyKey;

    // 동일 idempotencyKey로 바디(amount 등)가 다른 요청이 재전송되면 충돌로 판단(#7, A9 범위 환불까지 확장)
    private String requestHash;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    public enum Status {
        PENDING, COMPLETE, FAILED
    }
}
