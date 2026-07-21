package com.openat.payment.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PaymentEvent;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import com.openat.payment.domain.repository.WalletRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// WALLET 즉시승인의 DB 쓰기 전담 — PaymentFinalizer(confirmPg 꼬리)와 같은 역할의 짧은 TX 단위.
// 분리 이유: payWithWallet에 @Transactional을 걸면 Order 검증 HTTP 왕복 내내 DB 커넥션을 쥐게 된다
// (pool=2에서 즉시 고갈, osiv_throughput_analysis.md §2-3 [E]). HTTP는 PaymentService에서 TX 밖으로
// 빼고, 여기 남은 쓰기(차감·거래내역·결제·이벤트·outbox)만 한 트랜잭션으로 원자 처리한다.
// 자기호출(self-invocation)은 프록시를 타지 않으므로 별도 빈이어야 한다 — PaymentFinalizer와 동일한 이유.
@Component
public class WalletPaymentApprover {

  private static final String COMPLETED_TOPIC = "payment.completed.events";

  private final PaymentRepository paymentRepository;
  private final WalletRepository walletRepository;
  private final WalletTransactionRepository walletTransactionRepository;
  private final PaymentEventRepository paymentEventRepository;
  private final DomainEventPublisher eventPublisher;

  public WalletPaymentApprover(
      PaymentRepository paymentRepository,
      WalletRepository walletRepository,
      WalletTransactionRepository walletTransactionRepository,
      PaymentEventRepository paymentEventRepository,
      DomainEventPublisher eventPublisher) {
    this.paymentRepository = paymentRepository;
    this.walletRepository = walletRepository;
    this.walletTransactionRepository = walletTransactionRepository;
    this.paymentEventRepository = paymentEventRepository;
    this.eventPublisher = eventPublisher;
  }

  // 잔액 차감부터 outbox 적재까지 한 TX. outbox 발행이 같은 TX 안이어야 결제 저장과 원자적이다(PaymentFinalizer 동일).
  @Transactional
  public Payment deductAndApprove(PayWithWalletCommand command, String requestHash) {
    Wallet wallet = walletRepository.findOrCreateByMemberId(command.memberId());

    // 잔액 차감(#8) — SELECT-then-UPDATE 없이 단일 조건부 UPDATE로 원자처리.
    int affected = walletRepository.tryDeduct(wallet.getId(), command.amount());
    if (affected == 0) {
      throw new BusinessException(PaymentErrorCode.INSUFFICIENT_BALANCE);
    }
    // D3 — UPDATE 성공 후 같은 TX 재조회(row lock이 커밋까지 유지되므로 재조회 값이 정답, §4.2).
    long balanceAfter =
        walletRepository
            .findByMemberId(command.memberId())
            .map(Wallet::getBalance)
            .orElse(wallet.getBalance() - command.amount());

    LocalDateTime now = LocalDateTime.now();

    walletTransactionRepository.save(
        WalletTransaction.deductOf(
            wallet.getId(), command.amount(), balanceAfter, command.idempotencyKey()));

    Payment saved =
        paymentRepository.save(
            Payment.approvedWallet(
                command.orderId(),
                command.memberId(),
                command.amount(),
                command.idempotencyKey(),
                requestHash));

    paymentEventRepository.save(
        PaymentEvent.builder()
            .paymentId(saved.getId())
            .type(PaymentEvent.Type.APPROVE)
            .amount(command.amount())
            .createdAt(now)
            .build());

    // (Day2 남겨둔 항목, 2026-06-21 반영) WALLET 즉시승인도 PG confirm/웹훅 경로와 동일하게 payment.completed.events 발행.
    eventPublisher.publish(
        "PAYMENT",
        saved.getId(),
        COMPLETED_TOPIC,
        new PaymentCompletedPayload(
            saved.getId(),
            saved.getOrderId(),
            saved.getMemberId(),
            saved.getAmount(),
            saved.getMethod().name(),
            null,
            saved.getApprovedAt()));

    return saved;
  }
}
