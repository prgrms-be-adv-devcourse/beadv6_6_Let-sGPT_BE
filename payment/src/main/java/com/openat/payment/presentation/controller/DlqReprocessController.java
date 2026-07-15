package com.openat.payment.presentation.controller;

import com.openat.payment.application.dto.DlqReprocessResult;
import com.openat.payment.infrastructure.messaging.OrderCompletedDlqReprocessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// DLQ 수동 재처리 트리거(7/15 DLQ WS-1 DoD) — 원인 수정 후 운영자가 호출.
@Tag(name = "Dlq", description = "Kafka DLQ 수동 재처리")
@RestController
public class DlqReprocessController {

    private final OrderCompletedDlqReprocessor orderCompletedDlqReprocessor;

    public DlqReprocessController(OrderCompletedDlqReprocessor orderCompletedDlqReprocessor) {
        this.orderCompletedDlqReprocessor = orderCompletedDlqReprocessor;
    }

    @Operation(summary = "order.completed.events.DLQ 재처리",
            description = "최대 max건을 읽어 재처리한다. 실패 레코드가 있으면 그 이후 오프셋은 커밋하지 않아 다음 호출에서 다시 읽힌다.")
    @PostMapping("/internal/v1/dlq/order-completed/reprocess")
    public ResponseEntity<DlqReprocessResult> reprocessOrderCompleted(
            @RequestParam(defaultValue = "100") int max) {
        return ResponseEntity.ok(orderCompletedDlqReprocessor.reprocess(max));
    }
}
