package com.openat.payment.infrastructure.client;

import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 단위테스트용 스텁 — order-real 프로필 미활성화 시에만 등록, 클라이언트 제출값을 그대로 통과시킴.
@Component
@Profile("!order-real")
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
