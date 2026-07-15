package com.openat.payment.application.dto;

// DLQ 재처리 결과(7/15 DLQ WS-1) — polled 중 성공/실패 건수. failed>0이면 해당 레코드들은 다음 호출에서 다시 읽힌다
// (오프셋을 성공분까지만 커밋하기 때문 — DlqReprocessService 참고).
public record DlqReprocessResult(int polled, int reprocessed, int failed) {
}
