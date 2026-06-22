package com.openat.payment.domain.model;

import com.openat.payment.domain.model.support.UuidV7Generator;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 순수 도메인 모델(JPA 비의존) — 영속화는 infrastructure/persistence/entity/WalletChargeJpaEntity가 담당.
// pgPaymentKey는 도메인에선 평문 그대로 다룸 — 암호화는 순수 영속화 관심사라 JPA 엔티티 쪽 @Convert에서만 처리.
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WalletCharge {

    @Builder.Default
    private UUID id = UuidV7Generator.generate();

    private UUID memberId;

    private Long amount;

    private Method method;

    private Status status;

    private String pgPaymentKey;

    private String idempotencyKey;

    // 동일 idempotencyKey로 바디(amount/method)가 다른 요청이 재전송되면 충돌로 판단(#7)
    private String requestHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum Method {
        MOCK, PG
    }

    // CHARGED는 폐기, Payment.Status와 동일하게 APPROVED로 통일(qna.md Q16)
    public enum Status {
        PENDING, APPROVED, FAILED
    }
}
