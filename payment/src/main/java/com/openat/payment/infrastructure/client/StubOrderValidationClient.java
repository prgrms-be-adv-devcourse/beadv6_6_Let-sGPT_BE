package com.openat.payment.infrastructure.client;

import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

// B5(GET /internal/v1/orders/{orderId}) 요청은 주문팀에 전달됐지만 아직 API가 없어, 클라이언트 제출값을 그대로 통과시키는 스텁.
// 실 연동 시: WebClient에 타임아웃(2초)을 걸어 실제 호출하고, circuitBreaker로 연속실패 시 호출 자체를 차단하도록 교체.
@Component
public class StubOrderValidationClient implements OrderValidationClient {

    private final SimpleCircuitBreaker circuitBreaker =
            new SimpleCircuitBreaker(5, Duration.ofSeconds(30));

    @Override
    public OrderValidationResult validate(UUID orderId, UUID claimedMemberId, Long claimedAmount) {
        if (!circuitBreaker.allowRequest()) {
            // 실 연동 전이라 도달하지 않지만, 골격상 open 상태에선 검증을 통과시키지 않음.
            return new OrderValidationResult(claimedMemberId, claimedAmount, "UNKNOWN", false);
        }
        circuitBreaker.recordSuccess();
        return new OrderValidationResult(claimedMemberId, claimedAmount, "PAYMENT_PENDING", true);
    }
}
