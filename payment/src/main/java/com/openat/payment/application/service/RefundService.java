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
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 환불(§6) — 소유자 검증(Order 재호출 없음, Payment.memberId 내부 비교) + 환불가능액 조건부 UPDATE(#13)
// + PG 환불 호출 멱등키(#12). WALLET 결제는 PG 호출 없이 지갑으로 즉시 반환, PG 결제만 토스 결제취소 호출.
// 확정(complete/fail) 로직은 RefundFinalizer로 위임(7-12 plan WS-D) — 이 서비스는 접수와 PG 호출까지만 담당.
@Service
public class RefundService implements RefundUseCase {

  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final WalletRepository walletRepository;
  private final WalletTransactionRepository walletTransactionRepository;
  private final TossPaymentClient tossPaymentClient;
  private final RefundFinalizer refundFinalizer;

  public RefundService(
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      WalletRepository walletRepository,
      WalletTransactionRepository walletTransactionRepository,
      TossPaymentClient tossPaymentClient,
      RefundFinalizer refundFinalizer) {
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.walletRepository = walletRepository;
    this.walletTransactionRepository = walletTransactionRepository;
    this.tossPaymentClient = tossPaymentClient;
    this.refundFinalizer = refundFinalizer;
  }

  @Override
  @Transactional
  public RefundResult requestRefund(RefundCommand command) {
    String requestHash =
        RequestHasher.hash(command.paymentId().toString(), command.amount().toString());

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

    // 환불가능액 원자 검증(#13) — SELECT-then-UPDATE 없이 단일 조건부 UPDATE로 원자처리.
    int affected = paymentRepository.tryIncreaseRefundedAmount(payment.getId(), command.amount());
    if (affected == 0) {
      throw new BusinessException(PaymentErrorCode.EXCEED_REFUNDABLE_AMOUNT);
    }

    Refund pending =
        refundRepository.save(
            Refund.pending(
                payment.getId(),
                command.amount(),
                command.reason(),
                command.idempotencyKey(),
                requestHash));

    if (payment.getMethod() == Payment.Method.WALLET) {
      // WALLET 결제는 PG 호출 없음 — 지갑으로 즉시 반환.
      creditWallet(payment, pending);
      return toResult(refundFinalizer.complete(pending.getId(), payment, null), pending);
    }

    // PG 결제 — 환불 호출에도 멱등키 부착(#12). 이 호출은 원래도 동기 응답 구조라 confirm 같은 별도 단계 없음.
    TossRefundResult pgResult =
        tossPaymentClient.refundPayment(
            payment.getPgPaymentKey(), command.amount(), command.idempotencyKey());

    return switch (pgResult.status()) {
      case COMPLETE ->
          toResult(
              refundFinalizer.complete(pending.getId(), payment, pgResult.pgRefundKey()), pending);
      case FAILED ->
          toResult(refundFinalizer.fail(pending.getId(), payment, pgResult.reason()), pending);
      case UNKNOWN -> {
        // 타임아웃 등 응답 불확실 — 보정하지 않고 PENDING 유지, 보조 웹훅이 나중에 확정.
        yield new RefundResult(
            pending.getId(),
            pending.getPaymentId(),
            pending.getAmount(),
            pending.getStatus().name());
      }
    };
  }

  private void creditWallet(Payment payment, Refund refund) {
    Wallet wallet =
        walletRepository
            .findByMemberId(payment.getMemberId())
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    walletRepository.charge(wallet.getId(), refund.getAmount());
    // D3 — UPDATE 성공 후 같은 TX 재조회(row lock이 커밋까지 유지되므로 재조회 값이 정답, §4.2).
    long balanceAfter =
        walletRepository
            .findByMemberId(payment.getMemberId())
            .map(Wallet::getBalance)
            .orElse(wallet.getBalance() + refund.getAmount());

    walletTransactionRepository.save(
        WalletTransaction.refundOf(
            wallet.getId(), refund.getAmount(), balanceAfter, refund.getIdempotencyKey()));
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
