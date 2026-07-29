package com.openat.payment.domain.repository;

import java.time.LocalDateTime;
import java.util.UUID;

// TTL 스캐너 키셋 페이지네이션 커서 — createdAt 단독이면 동일 시각 행이 페이지 경계에 몰릴 때 나머지가
// 건너뛰어진다. (createdAt, id) 복합 커서로 동률을 깨서 같은 시각의 후속 행에도 반드시 도달하게 한다.
// null 커서 = 처음(가장 오래된 것)부터.
public record ScanCursor(LocalDateTime createdAt, UUID id) {}
