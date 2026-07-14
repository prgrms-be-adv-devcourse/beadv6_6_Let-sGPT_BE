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
// builder는 public 유지 + 팩토리(pendingPg/approvedWallet) 우선 사용 컨벤션(7-12 plan WS-C ★검수①) —
// JPA toDomain() 재수화가 여전히 builder를 쓰므로 완전 봉인은 후속 과제로 미룸.
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Payment {

  @Builder.Default private UUID id = UuidV7Generator.generate();

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
  @Builder.Default private Long refundedAmount = 0L;

  // 멱등키는 시도 단위로 발급(orderId 단독 아님)
  private String idempotencyKey;

  // 동일 idempotencyKey로 바디가 다른 요청이 재전송되면 충돌로 판단(#7) — amount/method/orderId 해시
  private String requestHash;

  // APPROVED 전이 시점 1회 기록(updatedAt과 분리)
  private LocalDateTime approvedAt;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  // PG 결제 접수 — PENDING row 생성용 팩토리(A16). 7-13 plan D1로 POST /payments의 PG 분기는 삭제됐고
  // main 코드 경로에서는 더 이상 호출되지 않음(confirm은 reserveForConfirm 사용) — 도메인 계약으로 남겨둠.
  public static Payment pendingPg(
      UUID orderId,
      UUID memberId,
      Long amount,
      String pgProvider,
      String idempotencyKey,
      String requestHash) {
    requirePositive(amount);
    LocalDateTime now = LocalDateTime.now();
    return Payment.builder()
        .orderId(orderId)
        .memberId(memberId)
        .amount(amount)
        .method(Method.PG)
        .pgProvider(pgProvider)
        .status(Status.PAYMENT_PENDING)
        .idempotencyKey(idempotencyKey)
        .requestHash(requestHash)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  // confirm 단일 진입점(7-13 plan WS-B) — order_id 유니크 예약 INSERT. 신-하자드9의 "키 선기록"이
  // INSERT 자체에 흡수되어 pgPaymentKey/Hash를 처음부터 채운다. hash는 도메인이 알고리즘에 의존하지 않도록
  // 호출측(application)이 계산해서 넘긴다.
  public static Payment reserveForConfirm(
      UUID orderId, UUID memberId, Long amount, String pgPaymentKey, String pgPaymentKeyHash) {
    requirePositive(amount);
    LocalDateTime now = LocalDateTime.now();
    return Payment.builder()
        .orderId(orderId)
        .memberId(memberId)
        .amount(amount)
        .method(Method.PG)
        .pgProvider("TOSS")
        .pgPaymentKey(pgPaymentKey)
        .pgPaymentKeyHash(pgPaymentKeyHash)
        .status(Status.PAYMENT_PENDING)
        .idempotencyKey("pay-key:" + pgPaymentKeyHash)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  // 지갑 결제 — 잔액 차감과 동시에 즉시 승인(PaymentService.payWithWallet).
  public static Payment approvedWallet(
      UUID orderId, UUID memberId, Long amount, String idempotencyKey, String requestHash) {
    requirePositive(amount);
    LocalDateTime now = LocalDateTime.now();
    return Payment.builder()
        .orderId(orderId)
        .memberId(memberId)
        .amount(amount)
        .method(Method.WALLET)
        .status(Status.APPROVED)
        .idempotencyKey(idempotencyKey)
        .requestHash(requestHash)
        .approvedAt(now)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  // 환불 가능 잔액 — SQL 조건절(tryIncreaseRefundedAmount)이 최종 방어선, 이건 의도 표현+1차 방어선.
  public long refundableAmount() {
    return amount - refundedAmount;
  }

  public boolean isFinalized() {
    return status != Status.PENDING && status != Status.PAYMENT_PENDING;
  }

  private static void requirePositive(Long amount) {
    if (amount == null || amount <= 0) {
      throw new IllegalArgumentException("결제 금액은 양수여야 합니다: " + amount);
    }
  }

  public enum Method {
    WALLET,
    PG
  }

  // DB 조건부 UPDATE(WHERE status=...)와 의도적으로 이중화된 전이 규칙 — 합법 전이를 코드로 문서화하고
  // 단위테스트 대상으로 만든다(code_review §2.3(b)). DB 조건절이 최종 방어선인 건 불변.
  public enum Status {
    PENDING {
      @Override
      public boolean canTransitionTo(Status next) {
        return next == PAYMENT_PENDING || next == FAILED || next == CANCELED;
      }
    },
    PAYMENT_PENDING {
      @Override
      public boolean canTransitionTo(Status next) {
        return next == APPROVED || next == FAILED || next == CANCELED;
      }
    },
    APPROVED {
      @Override
      public boolean canTransitionTo(Status next) {
        return next == REFUNDED || next == PARTIALLY_REFUNDED;
      }
    },
    PARTIALLY_REFUNDED {
      @Override
      public boolean canTransitionTo(Status next) {
        return next == REFUNDED || next == PARTIALLY_REFUNDED;
      }
    },
    FAILED,
    CANCELED,
    REFUNDED; // 종결 상태 — 기본 false(더 이상 전이 불가)

    public boolean canTransitionTo(Status next) {
      return false;
    }
  }
}
