package com.openat.payment.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossPaymentDetail;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.client.TossRefundResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

// G — 실제 토스페이먼츠 API 연동. TossPaymentClient(포트)의 유일한 구현체(스텁은 2026-07-17 제거).
@Slf4j
@Component
public class RealTossPaymentClient implements TossPaymentClient {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    // refundPayment 인터페이스에는 사유 파라미터가 없어 고정 사유를 사용(토스 cancelReason은 필수 필드).
    private static final String DEFAULT_CANCEL_REASON = "고객 요청에 의한 결제 취소";

    private final RestClient tossRestClient;
    private final MeterRegistry meterRegistry;

    public RealTossPaymentClient(@Qualifier("tossRestClient") RestClient tossRestClient,
            MeterRegistry meterRegistry) {
        this.tossRestClient = tossRestClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public TossConfirmResult confirmPayment(String paymentKey, UUID orderId, Long amount, String idempotencyKey) {
        return confirm(paymentKey, orderId.toString(), amount, idempotencyKey);
    }

    @Override
    public TossConfirmResult confirmCharge(String paymentKey, UUID chargeId, Long amount, String idempotencyKey) {
        return confirm(paymentKey, chargeId.toString(), amount, idempotencyKey);
    }

    // 결제/충전 confirm은 같은 토스 API(POST /v1/payments/confirm)를 공유 — orderId 필드에 chargeId를 그대로 매핑(G1).
    private TossConfirmResult confirm(String paymentKey, String orderId, Long amount, String idempotencyKey) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            TossConfirmResult result = tossRestClient.post()
                    .uri("/v1/payments/confirm")
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(new ConfirmRequest(paymentKey, orderId, amount))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            ConfirmResponse body = response.bodyTo(ConfirmResponse.class);
                            return TossConfirmResult.approved(body.lastTransactionKey());
                        }
                        if (response.getStatusCode().is4xxClientError()) {
                            return TossConfirmResult.rejected(readErrorMessage(response));
                        }
                        // 5xx — 신-하자드9 보정 로직(confirm이 PG호출까지 갔는지 모르는 상태)에 위임, 예외를 그대로 던짐(G2).
                        throw new IllegalStateException("토스 confirm 실패: status=" + response.getStatusCode());
                    });
            outcome = result.approved() ? "approved" : "rejected";
            return result;
        } finally {
            sample.stop(meterRegistry.timer("payment.pg.call", "operation", "confirm", "outcome", outcome));
        }
    }

    @Override
    public TossQueryResult queryPaymentStatus(String paymentKey) {
        return tossRestClient.get()
                .uri("/v1/payments/{paymentKey}", paymentKey)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        return TossQueryResult.of(TossQueryResult.Status.NOT_FOUND, null);
                    }
                    if (response.getStatusCode().is2xxSuccessful()) {
                        QueryResponse body = response.bodyTo(QueryResponse.class);
                        TossQueryResult.Status status = "DONE".equals(body.status())
                                ? TossQueryResult.Status.APPROVED
                                : TossQueryResult.Status.FAILED;
                        return TossQueryResult.of(status, body.lastTransactionKey());
                    }
                    // 5xx/그 외 — TTL스캐너가 예외로 받아 다음 주기 재시도(I2)하도록 그대로 던짐.
                    throw new IllegalStateException("토스 결제조회 실패: status=" + response.getStatusCode());
                });
    }

    @Override
    public TossPaymentDetail queryPaymentDetail(String pgPaymentKey) {
        // PG 대사(WS-0) — queryPaymentStatus와 같은 조회 API지만 totalAmount까지 파싱해 금액 대조에 쓴다.
        return tossRestClient.get()
                .uri("/v1/payments/{paymentKey}", pgPaymentKey)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        return TossPaymentDetail.of(TossPaymentDetail.Status.NOT_FOUND, null, null);
                    }
                    if (response.getStatusCode().is2xxSuccessful()) {
                        QueryDetailResponse body = response.bodyTo(QueryDetailResponse.class);
                        TossPaymentDetail.Status status = "DONE".equals(body.status())
                                ? TossPaymentDetail.Status.APPROVED
                                : TossPaymentDetail.Status.FAILED;
                        return TossPaymentDetail.of(status, body.totalAmount(), body.lastTransactionKey());
                    }
                    // 5xx/그 외 — PgReconciliationService가 예외로 받아 NOT_CHECKED 유지, 다음 배치에 재시도.
                    throw new IllegalStateException("토스 결제조회(대사) 실패: status=" + response.getStatusCode());
                });
    }

    @Override
    public TossRefundResult refundPayment(String pgPaymentKey, Long amount, String idempotencyKey) {
        try {
            return tossRestClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", pgPaymentKey)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(new CancelRequest(DEFAULT_CANCEL_REASON, amount))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            CancelResponse body = response.bodyTo(CancelResponse.class);
                            return TossRefundResult.complete(body.lastTransactionKey());
                        }
                        if (response.getStatusCode().is4xxClientError()) {
                            return TossRefundResult.failed(readErrorMessage(response));
                        }
                        // 5xx — UNKNOWN과 동일 취급(G2) — RefundService가 PENDING 유지로 처리, 보조 웹훅이 나중에 확정.
                        return TossRefundResult.unknown();
                    });
        } catch (ResourceAccessException e) {
            // 네트워크 오류/타임아웃도 UNKNOWN으로 동일 취급(G2).
            log.warn("[RealTossPaymentClient] 환불 호출 네트워크 오류, UNKNOWN 처리: pgPaymentKey={}", pgPaymentKey, e);
            return TossRefundResult.unknown();
        }
    }

    @Override
    public TossQueryResult queryRefundStatus(String pgPaymentKey, String pgRefundKey, Long amount) {
        // I1 — 토스는 환불 전용 조회 API가 없어 결제 조회(GET /v1/payments/{paymentKey}) 응답의
        // cancels 배열에서 pgRefundKey(취소 transactionKey)로 매칭해 판정한다.
        // pgRefundKey가 null이면(refundPayment 호출이 타임아웃돼 못 받은 케이스, plan.md P3·P4) amount로
        // cancels[]를 매칭하는 폴백을 쓴다.
        return tossRestClient.get()
                .uri("/v1/payments/{paymentKey}", pgPaymentKey)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        return TossQueryResult.of(TossQueryResult.Status.NOT_FOUND, null);
                    }
                    if (response.getStatusCode().is2xxSuccessful()) {
                        QueryWithCancelsResponse body = response.bodyTo(QueryWithCancelsResponse.class);
                        List<CancelDetail> cancels = body.cancels() != null ? body.cancels() : List.of();
                        return cancels.stream()
                                .filter(cancel -> pgRefundKey != null
                                        ? pgRefundKey.equals(cancel.transactionKey())
                                        : amount.equals(cancel.cancelAmount()))
                                .findFirst()
                                .map(cancel -> "DONE".equals(cancel.cancelStatus())
                                        ? TossQueryResult.of(TossQueryResult.Status.APPROVED, cancel.transactionKey())
                                        : TossQueryResult.of(TossQueryResult.Status.FAILED, cancel.transactionKey()))
                                .orElse(TossQueryResult.of(TossQueryResult.Status.NOT_FOUND, null));
                    }
                    // 5xx/그 외 — RefundWebhookHandler가 예외로 받아 PENDING 유지하도록 그대로 던짐(I1).
                    throw new IllegalStateException("토스 환불조회 실패: status=" + response.getStatusCode());
                });
    }

    private String readErrorMessage(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            TossErrorResponse error = response.bodyTo(TossErrorResponse.class);
            return error != null && error.message() != null ? error.message() : "PG_REJECTED";
        } catch (Exception e) {
            return "PG_REJECTED";
        }
    }

    private record ConfirmRequest(String paymentKey, String orderId, Long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfirmResponse(String lastTransactionKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryResponse(String status, String lastTransactionKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryDetailResponse(String status, String lastTransactionKey, Long totalAmount) {
    }

    private record CancelRequest(String cancelReason, Long cancelAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CancelResponse(String lastTransactionKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TossErrorResponse(String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryWithCancelsResponse(String status, String lastTransactionKey, List<CancelDetail> cancels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CancelDetail(String transactionKey, String cancelStatus, Long cancelAmount) {
    }
}
