package com.openat.payment.presentation.controller;

import com.openat.payment.application.dto.DummyPaymentCompletedEvent;
import com.openat.payment.application.dto.DummyPaymentRefundedEvent;
import com.openat.payment.infrastructure.devtools.DummySettlementEventPublisher;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 정산팀 컨슈머 연동 테스트 전용 — 운영 비즈니스 로직과 무관(personal_workplan/research.md §18, plan.md Q).
// local 프로파일 한정 — prod/dev-infra 등 다른 프로파일에서는 빈 자체가 등록되지 않음.
@Profile("local")
@RestController
public class DummySettlementEventController {

    private final DummySettlementEventPublisher publisher;

    public DummySettlementEventController(DummySettlementEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/internal/v1/test/settlement-events/payment-completed")
    public ResponseEntity<Integer> publishPaymentCompleted(
            @RequestParam(defaultValue = "1") int count,
            @RequestParam(required = false) UUID sellerId) {
        for (int i = 0; i < count; i++) {
            UUID resolvedSellerId = sellerId != null ? sellerId : UUID.randomUUID();
            publisher.publishPaymentCompleted(new DummyPaymentCompletedEvent(UUID.randomUUID().toString(),
                    "PAYMENT_COMPLETED", LocalDateTime.now(), UUID.randomUUID(), UUID.randomUUID(),
                    resolvedSellerId, UUID.randomUUID(), UUID.randomUUID(), 10000L, 10000L,
                    LocalDateTime.now()));
        }
        return ResponseEntity.ok(count);
    }

    @PostMapping("/internal/v1/test/settlement-events/payment-refunded")
    public ResponseEntity<Integer> publishPaymentRefunded(
            @RequestParam(defaultValue = "1") int count,
            @RequestParam(required = false) UUID sellerId) {
        for (int i = 0; i < count; i++) {
            UUID resolvedSellerId = sellerId != null ? sellerId : UUID.randomUUID();
            publisher.publishPaymentRefunded(new DummyPaymentRefundedEvent(UUID.randomUUID().toString(),
                    "PAYMENT_REFUNDED", LocalDateTime.now(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), resolvedSellerId, UUID.randomUUID(), 5000L, "단순변심",
                    LocalDateTime.now()));
        }
        return ResponseEntity.ok(count);
    }
}
