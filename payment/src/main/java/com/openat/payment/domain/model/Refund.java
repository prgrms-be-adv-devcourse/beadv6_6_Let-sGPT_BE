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
// builder public 유지 + 팩토리 우선 사용 컨벤션(7-12 plan WS-C, Payment와 동일 원칙).
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Refund {

  @Builder.Default private UUID id = UuidV7Generator.generate();

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

  // PG 대사(WS-0) — WALLET 환불은 PG 호출이 없어 완료 시점에 바로 MATCHED로 마킹(RefundService.creditWallet 이후).
  // PG 환불은 NOT_CHECKED로 시작해 PgReconciliationService가 토스와 대조한다.
  @Builder.Default private PgReconStatus pgReconStatus = PgReconStatus.NOT_CHECKED;

  private LocalDateTime pgReconciledAt;

  // 환불 접수 — 항상 PENDING(RefundService.requestRefund).
  public static Refund pending(
      UUID paymentId, Long amount, String reason, String idempotencyKey, String requestHash) {
    if (amount == null || amount <= 0) {
      throw new IllegalArgumentException("환불 금액은 양수여야 합니다: " + amount);
    }
    return Refund.builder()
        .paymentId(paymentId)
        .amount(amount)
        .status(Status.PENDING)
        .reason(reason)
        .idempotencyKey(idempotencyKey)
        .requestHash(requestHash)
        .createdAt(LocalDateTime.now())
        .build();
  }

  // Payment.Status와 동일 원칙의 전이 규칙 이중화(DB 조건부 UPDATE가 최종 방어선).
  public enum Status {
    PENDING {
      @Override
      public boolean canTransitionTo(Status next) {
        return next == COMPLETE || next == FAILED;
      }
    },
    COMPLETE,
    FAILED; // 종결 상태

    public boolean canTransitionTo(Status next) {
      return false;
    }
  }
}
