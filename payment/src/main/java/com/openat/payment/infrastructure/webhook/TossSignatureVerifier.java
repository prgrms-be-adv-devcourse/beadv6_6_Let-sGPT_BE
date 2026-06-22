package com.openat.payment.infrastructure.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 토스 공식 웹훅은 서명 헤더를 제공하지 않음 — "서명검증" 단계를 실제로 의미있게 시연하기 위해
// 공유 비밀키(PG_SECRET_KEY) 기반 HMAC-SHA256 자체 규약을 정의(연습/포트폴리오 목적, 토스 실연동 시 이 단계만 교체).
@Component
public class TossSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String secretKey;

    public TossSignatureVerifier(@Value("${pg.secret-key}") String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String expected = sign(rawBody);
        return constantTimeEquals(expected, signatureHeader);
    }

    private String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 서명 생성 실패", e);
        }
    }

    // 타이밍 공격 방지를 위해 String.equals 대신 상수 시간 비교 사용.
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
