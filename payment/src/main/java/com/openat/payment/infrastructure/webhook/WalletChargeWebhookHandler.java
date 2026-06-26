package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// E1 — PaymentWebhookHandler와 동일한 모양(Day1 템플릿 재사용). 충전 confirm이 누락된 건을 잡는 보조 채널.
// confirm 메인 구조라 이벤트 발행은 없음(api_event_specification.md상 충전 도메인엔 Kafka 발행 항목 자체가 없음) —
// 대신 confirmCharge와 동일하게 승인 시 Wallet 잔액 반영까지가 이 핸들러의 후속처리.
// I1 — 페이로드의 status는 신뢰하지 않고 tossPaymentClient.queryPaymentStatus 조회 결과로 최종 판정한다.
@Slf4j
@Component
public class WalletChargeWebhookHandler extends AbstractPgWebhookHandler<WalletCharge> {

    private final WalletChargeRepository walletChargeRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final ObjectMapper objectMapper;
    private final TossPaymentClient tossPaymentClient;

    public WalletChargeWebhookHandler(List<WebhookOutcomeListener> listeners,
            WalletChargeRepository walletChargeRepository, WalletRepository walletRepository,
            WalletTransactionRepository walletTransactionRepository, ObjectMapper objectMapper,
            TossPaymentClient tossPaymentClient) {
        super(listeners);
        this.walletChargeRepository = walletChargeRepository;
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.objectMapper = objectMapper;
        this.tossPaymentClient = tossPaymentClient;
    }

    @Override
    protected boolean checkIdempotency(WebhookRequest request) {
        TossWalletChargeWebhookPayload payload = parse(request);
        if (payload == null) {
            return false;
        }
        Optional<WalletCharge> charge = walletChargeRepository.findByPgPaymentKey(payload.paymentKey());
        return charge.isPresent() && charge.get().getStatus() != WalletCharge.Status.PENDING;
    }

    @Override
    protected UpdateResult<WalletCharge> applyConditionalUpdate(WebhookRequest request) {
        TossWalletChargeWebhookPayload payload = parse(request);
        if (payload == null) {
            return UpdateResult.failure(null, null);
        }
        Optional<WalletCharge> maybeCharge = walletChargeRepository.findByPgPaymentKey(payload.paymentKey());
        if (maybeCharge.isEmpty()) {
            log.warn("[WalletChargeWebhookHandler] 매칭되는 WalletCharge 없음: paymentKey={}", payload.paymentKey());
            return UpdateResult.failure(null, null);
        }

        WalletCharge charge = maybeCharge.get();

        // I1 — 페이로드의 status는 트리거 신호로만 쓰고, 실제 판정은 PG 조회 결과로 한다.
        TossQueryResult queryResult;
        try {
            queryResult = tossPaymentClient.queryPaymentStatus(payload.paymentKey());
        } catch (Exception e) {
            log.warn("[WalletChargeWebhookHandler] 웹훅 재검증 조회 실패, PENDING 유지: chargeId={}", charge.getId(), e);
            return UpdateResult.failure(null, null);
        }

        WalletCharge.Status newStatus =
                queryResult.status() == TossQueryResult.Status.APPROVED
                        ? WalletCharge.Status.APPROVED
                        : WalletCharge.Status.FAILED;

        int affected = walletChargeRepository.tryTransitionFromPending(charge.getId(), newStatus, queryResult.pgTxId());
        if (affected == 0) {
            return UpdateResult.failure(charge.getId(), charge);
        }

        WalletCharge updated = walletChargeRepository.findById(charge.getId()).orElse(charge);
        return newStatus == WalletCharge.Status.APPROVED
                ? UpdateResult.success(updated.getId(), updated)
                : UpdateResult.failure(updated.getId(), updated);
    }

    @Override
    protected void onSuccess(UpdateResult<WalletCharge> result) {
        WalletCharge charge = result.getPayload();
        // confirmCharge와 동일한 후속처리 — 승인 즉시 Wallet 잔액 반영.
        Wallet wallet = walletRepository.findByMemberId(charge.getMemberId())
                .orElseGet(() -> walletRepository.save(Wallet.builder()
                        .memberId(charge.getMemberId())
                        .balance(0L)
                        .createdAt(LocalDateTime.now())
                        .build()));
        walletRepository.charge(wallet.getId(), charge.getAmount());
        long balanceAfter = wallet.getBalance() + charge.getAmount();

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(WalletTransaction.Type.CHARGE)
                .amount(charge.getAmount())
                .balanceAfter(balanceAfter)
                .idempotencyKey(charge.getIdempotencyKey())
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Override
    protected void onFailure(UpdateResult<WalletCharge> result) {
        // 이벤트 발행 없음(api_event_specification.md상 충전 도메인엔 Kafka 발행 항목 자체가 없음) — 상태 전이로 끝.
    }

    @Override
    protected String handlerType() {
        return "WALLET_CHARGE";
    }

    private TossWalletChargeWebhookPayload parse(WebhookRequest request) {
        try {
            return objectMapper.readValue(request.getRawBody(), TossWalletChargeWebhookPayload.Envelope.class).data();
        } catch (Exception e) {
            log.error("[WalletChargeWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
            return null;
        }
    }
}
