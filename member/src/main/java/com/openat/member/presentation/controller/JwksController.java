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
 * gateway 경로 "/member/oauth2/jwks"가 StripPrefix=1로 줄어든 결과가 이 경로("/oauth2/jwks")다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RSAPublicKey rsaPublicKey;
    private final JwtProperties jwtProperties;

    @GetMapping("/oauth2/jwks")
    public Map<String, Object> jwks() {
        RSAKey rsaKey = new RSAKey.Builder(rsaPublicKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(jwtProperties.keyId())
                .build();
        return new JWKSet(rsaKey).toJSONObject();
    }
}
