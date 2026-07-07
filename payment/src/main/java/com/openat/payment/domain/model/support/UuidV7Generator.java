package com.openat.payment.domain.model.support;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

// project_guide.md 기술스택 컨벤션(PK UUIDv7) 준수용 — UUID.randomUUID()는 v4라 시간순 정렬이 안 됨
public final class UuidV7Generator {

    private UuidV7Generator() {
    }

    public static UUID generate() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
