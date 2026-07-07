package com.openat.payment.application.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 동일 idempotencyKey로 바디가 다른 요청이 재전송되면 충돌로 판단(#7)하기 위한 핵심 필드 해시.
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = String.join("|", parts);
            byte[] hashed = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
