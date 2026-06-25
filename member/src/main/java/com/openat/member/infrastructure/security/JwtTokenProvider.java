package com.openat.member.infrastructure.security;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * RSA(RS256)로 access/refresh 토큰을 발급/검증한다.
 * 공개키는 {@code presentation.controller.JwksController}가 JWKS로 노출해 apigateway가 검증에 사용한다.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TOKEN_ID = "jti";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final RSAPrivateKey rsaPrivateKey;
    private final RSAPublicKey rsaPublicKey;
    private final JwtProperties jwtProperties;

    /**
     * @param member      토큰 subject(memberId) 추출용
     * @param currentRole role_history에서 조회한 현재 유효 역할
     */
    public String createAccessToken(Member member, Role currentRole) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(jwtProperties.keyId()).and()
                .subject(member.getId().toString())
                // apigateway의 jwtAuthenticationConverter가 "ROLE_"을 직접 붙이므로
                // 여기엔 접두사 없는 이름("USER"/"SELLER"/"ADMIN")만 담는다.
                .claim(CLAIM_ROLES, List.of(currentRole.bareName()))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(jwtProperties.accessTokenExpireSeconds())))
                .signWith(rsaPrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    public String createRefreshToken(UUID memberId, String tokenId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(jwtProperties.keyId()).and()
                .subject(memberId.toString())
                .claim(CLAIM_TOKEN_ID, tokenId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(jwtProperties.refreshTokenExpireSeconds())))
                .signWith(rsaPrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 서명/만료를 검증하고 클레임을 반환한다. {@code typ}이 "refresh"가 아니면
     * (access 토큰을 잘못 넣은 경우) {@code IllegalArgumentException}을 던진다.
     * 서명/만료 오류는 {@link io.jsonwebtoken.JwtException}(unchecked)으로 그대로 전파되며,
     * 호출하는 쪽({@code MemberService})에서 도메인 예외로 변환한다.
     */
    public Claims parseRefreshToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(rsaPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new IllegalArgumentException("refresh 토큰이 아닙니다.");
        }
        return claims;
    }

    public String getTokenId(Claims claims) {
        return claims.get(CLAIM_TOKEN_ID, String.class);
    }

    public long getAccessTokenExpireSeconds() {
        return jwtProperties.accessTokenExpireSeconds();
    }

    public long getRefreshTokenExpireSeconds() {
        return jwtProperties.refreshTokenExpireSeconds();
    }
}
