package com.openat.payment.infrastructure.client;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// 라이브러리(resilience4j 등) 없이 만든 최소 서킷브레이커 — 연속 실패 N회 시 open, 쿨다운 후 half-open(1회 시도) 허용.
// 외부 API(B5 등)가 실제로 붙기 전까지는 거의 호출되지 않는 골격 코드.
public class SimpleCircuitBreaker {

    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public SimpleCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public boolean allowRequest() {
        Instant opened = openedAt.get();
        if (opened == null) {
            return true;
        }
        if (Instant.now().isAfter(opened.plus(openDuration))) {
            return true; // half-open: 1회 시도 허용
        }
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAt.set(null);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openedAt.set(Instant.now());
        }
    }
}
