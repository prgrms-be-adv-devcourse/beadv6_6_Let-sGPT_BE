package com.openat.settlement.infrastructure.client;

import com.openat.settlement.infrastructure.client.dto.DailyPaymentSettlementResponse;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// payment의 정산 대사 일별 API 호출(WS-3, reconciliation.md 실행 흐름 "결제 모듈 API 호출").
@Component
public class PaymentReconciliationClient {

    private final RestClient restClient;

    public PaymentReconciliationClient(@Qualifier("paymentReconciliationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public DailyPaymentSettlementResponse getDaily(LocalDate businessDate) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/v1/payment-settlement/daily")
                        .queryParam("businessDate", businessDate.toString())
                        .build())
                .retrieve()
                .body(DailyPaymentSettlementResponse.class);
    }
}
