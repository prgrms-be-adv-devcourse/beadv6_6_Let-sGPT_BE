package com.openat.payment.domain.model;

import com.openat.payment.domain.model.support.UuidV7Generator;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

// 순수 도메인 모델(JPA 비의존) — 영속화는 infrastructure/persistence/entity/PaymentJpaEntity가 담당.
// pgPaymentKey는 도메인에선 평문 그대로 다룸 — 암호화는 순수 영속화 관심사라 JPA 엔티티 쪽 @Convert에서만 처리.
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Payment {

    @Builder.Default
    private UUID id = UuidV7Generator.generate();

    // 한 주문에 여러 시도(행)가 있을 수 있어 not unique — "성공 1건" 보장은 부분 유니크 인덱스(DB)가 담당
    private UUID orderId;

    private UUID memberId;

    // 생성 시점엔 비워두고 order_completed 이벤트로 사후 채움(B2)
    private UUID sellerId;

    private UUID productId;

    private Long amount;

    private Method method;

    private String pgProvider;

    private String pgPaymentKey;

    // pgPaymentKey는 암호화(비결정적 IV)되어 등호 조회가 불가능 — 웹훅 매칭은 이 평문 해시(결정적)로 수행
    private String pgPaymentKeyHash;

    // 웹훅 중복 수신 판단 기준
    private String pgTxId;

    private Status status;

    // 누적 환불 금액(의도적 비정규화) — 갱신은 조건부 UPDATE(refundedAmount+? <= amount)
    @Builder.Default
    private Long refundedAmount = 0L;

    // 멱등키는 시도 단위로 발급(orderId 단독 아님)
    private String idempotencyKey;

    // 동일 idempotencyKey로 바디가 다른 요청이 재전송되면 충돌로 판단(#7) — amount/method/orderId 해시
    private String requestHash;

    // APPROVED 전이 시점 1회 기록(updatedAt과 분리)
    private LocalDateTime approvedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public enum Method {
        WALLET, PG
    }

    public enum Status {
        PENDING, PAYMENT_PENDING, APPROVED, FAILED, CANCELED, REFUNDED, PARTIALLY_REFUNDED
    }
}
