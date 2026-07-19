package com.openat.payment.application.service;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.ChargeConfirmCommand;
import com.openat.payment.application.dto.ChargePgCommand;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.application.usecase.WalletChargeUseCase;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// MOCK 충전(§4)은 PG 의존 없는 가장 단순한 흐름, 항상 즉시 APPROVED. PG 충전(E1)은 confirmPg와 동일한
// confirm 메인 구조를 처음부터 적용(Order 검증만 없음 — 충전엔 orderId가 없어 #17 비대상).
// 확정(confirmCharge 꼬리) 로직은 WalletChargeFinalizer로 위임(7-12 plan WS-D).
@Service
public class WalletChargeService implements WalletChargeUseCase {

  private final WalletChargeRepository walletChargeRepository;
  private final WalletRepository walletRepository;
  private final WalletTransactionRepository walletTransactionRepository;
  private final TossPaymentClient tossPaymentClient;
  private final WalletChargeFinalizer walletChargeFinalizer;

  public WalletChargeService(
      WalletChargeRepository walletChargeRepository,
      WalletRepository walletRepository,
      WalletTransactionRepository walletTransactionRepository,
      TossPaymentClient tossPaymentClient,
      WalletChargeFinalizer walletChargeFinalizer) {
    this.walletChargeRepository = walletChargeRepository;
    this.walletRepository = walletRepository;
    this.walletTransactionRepository = walletTransactionRepository;
    this.tossPaymentClient = tossPaymentClient;
    this.walletChargeFinalizer = walletChargeFinalizer;
  }

  @Override
  @Transactional
  public WalletChargeResult chargeMock(ChargeWalletCommand command) {
    String requestHash =
        RequestHasher.hash(
            command.memberId().toString(),
            command.amount().toString(),
            WalletCharge.Method.MOCK.name());

    Optional<WalletCharge> existing =
        walletChargeRepository.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), requestHash);
    }

    Wallet wallet = walletRepository.findOrCreateByMemberId(command.memberId());
    walletRepository.charge(wallet.getId(), command.amount());
    // D3 — UPDATE 성공 후 같은 TX 재조회(row lock이 커밋까지 유지되므로 재조회 값이 정답, §4.2).
    long balanceAfter =
        walletRepository
            .findByMemberId(command.memberId())
            .map(Wallet::getBalance)
            .orElse(wallet.getBalance() + command.amount());

    walletTransactionRepository.save(
        WalletTransaction.chargeOf(
            wallet.getId(), command.amount(), balanceAfter, command.idempotencyKey()));

    WalletCharge saved =
        walletChargeRepository.save(
            WalletCharge.approvedMock(
                command.memberId(), command.amount(), command.idempotencyKey(), requestHash));

    return new WalletChargeResult(saved.getId(), saved.getStatus().name(), saved.getAmount());
  }

  @Override
  @Transactional
  public WalletChargeResult chargePg(ChargePgCommand command) {
    String requestHash =
        RequestHasher.hash(
            command.memberId().toString(),
            command.amount().toString(),
            WalletCharge.Method.PG.name());

    Optional<WalletCharge> existing =
        walletChargeRepository.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), requestHash);
    }

    // payWithPg와 동일 원칙(A16) — 서버는 PG에 아무것도 요청하지 않음. PENDING row만 만들고 끝
    // (pgPaymentKey는 confirmCharge에서 confirm 요청으로 전달받아 채움).
    WalletCharge pending =
        walletChargeRepository.save(
            WalletCharge.pendingPg(
                command.memberId(), command.amount(), command.idempotencyKey(), requestHash));

    return new WalletChargeResult(pending.getId(), pending.getStatus().name(), pending.getAmount());
  }

  // confirmPg와 동일한 TX 분리 원칙(D5) — 메서드에 @Transactional을 걸지 않는다. 걸면 토스 confirm HTTP 왕복
  // 내내 DB 커넥션을 점유한다(pool=2에서 즉시 고갈, osiv_throughput_analysis §2-3 [E]). TX는 아래 세 단위로 분리:
  // [예약 TX1] reservePgPaymentKeyIfPending -> [TX 밖] 토스 confirm -> [확정 TX2] WalletChargeFinalizer.
  @Override
  public WalletChargeResult confirmCharge(ChargeConfirmCommand command) {
    // confirmPg와 동일 원칙(하자드20) — 여기서는 #7(바디해시 대조)을 쓰지 않고 status 기준으로만 멱등성을 판단한다.
    // [1] 조회·소유자검증·멱등 조기반환(TX 밖 — 읽기, 커넥션 비점유).
    WalletCharge charge =
        walletChargeRepository
            .findById(command.chargeId())
            .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

    if (!Objects.equals(charge.getMemberId(), command.memberId())) {
      throw new BusinessException(PaymentErrorCode.FORBIDDEN);
    }

    if (charge.getStatus() != WalletCharge.Status.PENDING) {
      // 이미 다른 confirm 호출(재시도)이나 보조 웹훅이 먼저 확정함 — 멱등 반환, PG 재호출 없음.
      return new WalletChargeResult(charge.getId(), charge.getStatus().name(), charge.getAmount());
    }

    // [2] 예약(TX1) — 신-하자드9와 동일 원칙으로 PG 호출 *전에* pgPaymentKey를 기록하되, WHERE status='PENDING'
    // 조건부 UPDATE로 선점한다. affected=0이면 그 사이 다른 경로가 확정한 것 — PG 재호출 없이 현재 상태를 반환.
    int reserved =
        walletChargeRepository.reservePgPaymentKeyIfPending(charge.getId(), command.paymentKey());
    if (reserved == 0) {
      WalletCharge current = walletChargeRepository.findById(charge.getId()).orElse(charge);
      return new WalletChargeResult(
          current.getId(), current.getStatus().name(), current.getAmount());
    }

    // [3] 토스 confirm(TX 밖, DB 커넥션 비점유) — A10과 동일 원칙으로 멱등키를 confirm 호출에 부착.
    // 타임아웃/네트워크 예외는 삼키지 않고 그대로 전파 — row는 PENDING(+pgPaymentKey)로 남아 TTL 스캐너가 수렴한다.
    TossConfirmResult confirmResult =
        tossPaymentClient.confirmCharge(
            command.paymentKey(), command.chargeId(), command.amount(), command.idempotencyKey());

    WalletCharge.Status newStatus =
        confirmResult.approved() ? WalletCharge.Status.APPROVED : WalletCharge.Status.FAILED;

    // [4] 확정(TX2) — 하자드10과 동일 원칙, WalletChargeFinalizer의 조건부 UPDATE로 원자처리(7-12 plan WS-D).
    // lost-race면 이 스레드의 계산값이 아니라 현재 DB상태를 그대로 반환.
    WalletCharge updated =
        walletChargeFinalizer
            .finalizePending(charge.getId(), newStatus, confirmResult.pgTxId())
            .orElseGet(() -> walletChargeRepository.findById(charge.getId()).orElse(charge));

    return new WalletChargeResult(updated.getId(), updated.getStatus().name(), updated.getAmount());
  }

  private WalletChargeResult replayOrConflict(WalletCharge existing, String requestHash) {
    if (!Objects.equals(existing.getRequestHash(), requestHash)) {
      throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }
    return new WalletChargeResult(
        existing.getId(), existing.getStatus().name(), existing.getAmount());
  }
}
