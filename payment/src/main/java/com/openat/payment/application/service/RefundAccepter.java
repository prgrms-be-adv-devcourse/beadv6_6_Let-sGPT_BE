package com.openat.payment.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import com.openat.common.error.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 환불 접수(TX1)의 DB 쓰기 전담 — WalletPaymentApprover/Finalizer와 같은 역할의 짧은 TX 단위.
// 분리 이유: requestRefund에 @Transactional을 걸면 토스 환불 HTTP 왕복 내내 DB 커넥션을 쥐게 된다
// (pool=2에서 즉시 고갈, osiv_throughput_analysis §2-3 [E]). HTTP는 RefundService에서 TX 밖으로 빼고,
// 여기 남은 쓰기(환불액 선증가·Refund PENDING 저장)만 한 트랜잭션으로 원자 처리한다.
// 자기호출(self-invocation)은 프록시를 안 타므로 RefundService와 별도 빈이어야 한다 — Finalizer와 동일한 이유.
// WALLET 결제 환불은 PG 호출이 없어 이 TX 안에서 지갑 반영·대사 마킹·complete까지 끝낸다(기존 동작 유지).
@Component
public class RefundAccepter {

  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final WalletRepository walletRepository;
  private final WalletTransactionRepository walletTransactionRepository;
  private final RefundFinalizer refundFinalizer;

  public RefundAccepter(
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      WalletRepository walletRepository,
      WalletTransactionRepository walletTransactionRepository,
      RefundFinalizer refundFinalizer) {
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.walletRepository = walletRepository;
    this.walletTransactionRepository = walletTransactionRepository;
    this.refundFinalizer = refundFinalizer;
  }

  // 환불액 조건부 선증가 + Refund PENDING 저장을 한 TX로 원자 처리(HTTP 없음). 두 쓰기가 같은 TX여야
  // idempotencyKey 유니크 충돌 시 선증가까지 함께 롤백돼 이중 환불이 방지된다(분리 전 단일 TX와 동일 보장).
  @Transactional
  public Acceptance accept(RefundCommand command, Payment payment, String requestHash) {
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
      // PG 대사(WS-0) — WALLET 환불은 대조할 PG 거래가 없어 바로 MATCHED 확정.
      refundRepository.markPgReconMatched(pending.getId(), LocalDateTime.now());
      Refund completed =
          refundFinalizer
              .complete(pending.getId(), payment, null)
              .orElseGet(() -> refundRepository.findById(pending.getId()).orElse(pending));
      return Acceptance.terminal(
          new RefundResult(
              completed.getId(),
              completed.getPaymentId(),
              completed.getAmount(),
              completed.getStatus().name()));
    }

    return Acceptance.pendingPg(pending);
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

  // 접수 결과 — WALLET 즉시완료(terminal)면 그 결과를, PG 결제(pendingPg)면 이어서 HTTP를 태울 PENDING Refund를 담는다.
  public record Acceptance(RefundResult terminalResult, Refund pending) {

    static Acceptance terminal(RefundResult result) {
      return new Acceptance(result, null);
    }

    static Acceptance pendingPg(Refund pending) {
      return new Acceptance(null, pending);
    }

    public boolean isTerminal() {
      return terminalResult != null;
    }
  }
}
