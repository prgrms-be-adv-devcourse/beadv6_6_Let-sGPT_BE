package com.openat.payment.infrastructure.webhook;

import java.util.UUID;
import lombok.Getter;

// applyConditionalUpdate()의 결과 — affected rows=0(조건 불일치)이면 failure로 본다(§9 #10/#13 가드와 동일 기준).
@Getter
public final class UpdateResult<T> {

    private final boolean success;

    private final UUID referenceId;

    private final T payload;

    private UpdateResult(boolean success, UUID referenceId, T payload) {
        this.success = success;
        this.referenceId = referenceId;
        this.payload = payload;
    }

    public static <T> UpdateResult<T> success(UUID referenceId, T payload) {
        return new UpdateResult<>(true, referenceId, payload);
    }

    public static <T> UpdateResult<T> failure(UUID referenceId, T payload) {
        return new UpdateResult<>(false, referenceId, payload);
    }
}
