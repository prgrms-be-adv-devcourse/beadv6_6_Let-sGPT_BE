package com.openat.settlement.application.dto;

// DLQ 재처리 결과(7/15 DLQ WS-1) — payment 모듈의 동명 record와 동일 계약.
public record DlqReprocessResult(int polled, int reprocessed, int failed) {
}
