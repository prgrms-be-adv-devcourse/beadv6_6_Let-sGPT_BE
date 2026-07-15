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
// builder public 유지 + 팩토리 우선 사용 컨벤션(7-12 plan WS-C, Payment와 동일 원칙).
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class WalletCharge {

  @Builder.Default private UUID id = UuidV7Generator.generate();

  private UUID memberId;

  private Long amount;

  private Method method;

  private Status status;

  private String pgPaymentKey;

  // pgPaymentKey는 암호화(비결정적 IV)되어 등호 조회가 불가능 — 웹훅 매칭은 이 평문 해시(결정적)로 수행
  private String pgPaymentKeyHash;

  // 웹훅 중복 수신 판단 기준
  private String pgTxId;

  private String idempotencyKey;

  // 동일 idempotencyKey로 바디(amount/method)가 다른 요청이 재전송되면 충돌로 판단(#7)
  private String requestHash;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  // PG 충전 접수 — PENDING row(WalletChargeService.chargePg, payWithPg와 동일 원칙 A16).
  public static WalletCharge pendingPg(
      UUID memberId, Long amount, String idempotencyKey, String requestHash) {
    requirePositive(amount);
    LocalDateTime now = LocalDateTime.now();
    return WalletCharge.builder()
        .memberId(memberId)
        .amount(amount)
        .method(Method.PG)
        .status(Status.PENDING)
        .idempotencyKey(idempotencyKey)
        .requestHash(requestHash)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  // MOCK 충전 — PG 의존 없이 항상 즉시 APPROVED(WalletChargeService.chargeMock).
  public static WalletCharge approvedMock(
      UUID memberId, Long amount, String idempotencyKey, String requestHash) {
    requirePositive(amount);
    LocalDateTime now = LocalDateTime.now();
    return WalletCharge.builder()
        .memberId(memberId)
        .amount(amount)
        .method(Method.MOCK)
        .status(Status.APPROVED)
        .idempotencyKey(idempotencyKey)
        .requestHash(requestHash)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private static void requirePositive(Long amount) {
    if (amount == null || amount <= 0) {
      throw new IllegalArgumentException("충전 금액은 양수여야 합니다: " + amount);
    }
  }

  public enum Method {
    MOCK,
    PG
  }

  // CHARGED는 폐기, Payment.Status와 동일하게 APPROVED로 통일(qna.md Q16)
  // Payment.Status와 동일 원칙의 전이 규칙 이중화(DB 조건부 UPDATE가 최종 방어선).
  public enum Status {
    PENDING {
      @Override
      public boolean canTransitionTo(Status next) {
        return next == APPROVED || next == FAILED;
      }
    },
    APPROVED,
    FAILED; // 종결 상태

    public boolean canTransitionTo(Status next) {
      return false;
    }
  }
}
