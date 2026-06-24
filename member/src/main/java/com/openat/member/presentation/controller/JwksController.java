package com.openat.member.presentation.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.openat.member.infrastructure.security.JwtProperties;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * apigateway의 {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}가
 * 이 엔드포인트를 호출해 RSA 공개키를 가져가 JWT 서명을 검증한다.
 * gateway의 member route({@code Path=/api/v1/members/**,/api/v1/seller/**,/auth/jwks})가
 * StripPrefix 없이 그대로 전달하므로 컨트롤러 경로도 동일하게 "/auth/jwks"다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RSAPublicKey rsaPublicKey;
    private final JwtProperties jwtProperties;

    @GetMapping("/auth/jwks")
    public Map<String, Object> jwks() {
        RSAKey rsaKey = new RSAKey.Builder(rsaPublicKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(jwtProperties.keyId())
                .build();
        return new JWKSet(rsaKey).toJSONObject();
    }
}
