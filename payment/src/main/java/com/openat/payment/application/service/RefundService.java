package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossRefundResult;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.application.usecase.RefundUseCase;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 환불(§6) — 소유자 검증(Order 재호출 없음, Payment.memberId 내부 비교) + 환불가능액 조건부 UPDATE(#13)
// + PG 환불 호출 멱등키(#12). WALLET 결제는 PG 호출 없이 지갑으로 즉시 반환, PG 결제만 토스 결제취소 호출.
// confirmPg와 동일한 TX 분리 원칙(D5) — 메서드에 @Transactional을 걸지 않는다. 걸면 토스 환불 HTTP 왕복 내내
// DB 커넥션을 점유한다(pool=2에서 즉시 고갈). TX는 아래 세 단위로 분리한다:
// [접수 TX1] RefundAccepter.accept(환불액 선증가 + PENDING 저장, WALLET은 즉시완료) -> [TX 밖] 토스 환불
// -> [확정 TX2] RefundFinalizer.complete/fail(fail은 내부에서 환불액 원복까지 수행).
@Service
public class RefundService implements RefundUseCase {

  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final TossPaymentClient tossPaymentClient;
  private final RefundFinalizer refundFinalizer;
  private final RefundAccepter refundAccepter;

  public RefundService(
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      TossPaymentClient tossPaymentClient,
      RefundFinalizer refundFinalizer,
      RefundAccepter refundAccepter) {
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.tossPaymentClient = tossPaymentClient;
    this.refundFinalizer = refundFinalizer;
    this.refundAccepter = refundAccepter;
  }

  @Override
  public RefundResult requestRefund(RefundCommand command) {
    String requestHash =
        RequestHasher.hash(command.paymentId().toString(), command.amount().toString());

    // [1] 멱등 재생 확인·소유자 검증(TX 밖 — 읽기, 커넥션 비점유). 실제 동시성 가드는 idempotencyKey 유니크
    // 제약(accept의 save)에 있다 — 여기 읽기는 흔한 재생 케이스를 아끼기 위한 조기 반환.
    Optional<Refund> existing = refundRepository.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), requestHash);
    }

    Payment payment =
        paymentRepository
            .findById(command.paymentId())
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

    // 소유자 검증 — Order 재호출 없음(이미 결제 시점에 검증된 memberId와 내부 비교만, api_event_specification.md 403).
    if (!Objects.equals(payment.getMemberId(), command.memberId())) {
      throw new BusinessException(PaymentErrorCode.FORBIDDEN);
    }

    // [2] 접수(TX1) — 환불액 조건부 선증가 + Refund PENDING 저장을 원자 처리. WALLET은 이 TX에서 즉시 완료돼 반환된다.
    RefundAccepter.Acceptance acceptance = refundAccepter.accept(command, payment, requestHash);
    if (acceptance.isTerminal()) {
      return acceptance.terminalResult();
    }
    Refund pending = acceptance.pending();

    // [3] PG 결제 — 토스 환불(TX 밖, DB 커넥션 비점유). 환불 호출에도 멱등키 부착(#12).
    // 타임아웃/네트워크 예외는 삼키지 않고 그대로 전파 — Refund는 PENDING으로 남아 보조 웹훅/TTL 스캐너가 수렴한다.
    TossRefundResult pgResult =
        tossPaymentClient.refundPayment(
            payment.getPgPaymentKey(), command.amount(), command.idempotencyKey());

    // [4] 확정(TX2) — RefundFinalizer 재사용. 명확 실패(fail)는 내부에서 환불액 원복(tryDecreaseRefundedAmount)까지
    // 수행하므로 분리로 사라진 롤백 보상이 여기서 명시적으로 이뤄진다.
    return switch (pgResult.status()) {
      case COMPLETE ->
          toResult(
              refundFinalizer.complete(pending.getId(), payment, pgResult.pgRefundKey()), pending);
      case FAILED ->
          toResult(refundFinalizer.fail(pending.getId(), payment, pgResult.reason()), pending);
      case UNKNOWN -> {
        // 타임아웃 등 응답 불확실 — 보정하지 않고 PENDING 유지, 보조 웹훅/TTL 스캐너가 나중에 확정.
        yield new RefundResult(
            pending.getId(),
            pending.getPaymentId(),
            pending.getAmount(),
            pending.getStatus().name());
      }
    };
  }

  // Finalizer가 lost-race(Optional.empty)면 현재 DB상태를 그대로 반환(confirmPg와 동일 원칙).
  private RefundResult toResult(Optional<Refund> finalized, Refund pending) {
    Refund current =
        finalized.orElseGet(() -> refundRepository.findById(pending.getId()).orElse(pending));
    return new RefundResult(
        current.getId(), current.getPaymentId(), current.getAmount(), current.getStatus().name());
  }

  @Override
  public RefundResult getRefund(UUID refundId) {
    Refund refund =
        refundRepository
            .findById(refundId)
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    return new RefundResult(
        refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getStatus().name());
  }

  @Override
  public RefundHistoryResult getRefundHistories(UUID memberId, int page, int size) {
    List<Refund> refunds = refundRepository.findByMemberId(memberId, page, size);
    long totalCount = refundRepository.countByMemberId(memberId);
    int totalPages = (int) Math.ceil(totalCount / (double) size);

    List<RefundResult> content =
        refunds.stream()
            .map(
                r ->
                    new RefundResult(
                        r.getId(), r.getPaymentId(), r.getAmount(), r.getStatus().name()))
            .toList();

    return new RefundHistoryResult(content, totalPages);
  }

  private RefundResult replayOrConflict(Refund existing, String requestHash) {
    if (!Objects.equals(existing.getRequestHash(), requestHash)) {
      throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
    return new RefundResult(
        existing.getId(),
        existing.getPaymentId(),
        existing.getAmount(),
        existing.getStatus().name());
  }
}
