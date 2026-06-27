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
import java.util.Map;
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
    private static final String CLAIM_ACT = "act";
    private static final String CLAIM_SCOPE = "scope";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String TYPE_SCOPED = "scoped";

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
     * RFC 8693 §4.1 delegation 모델로 scoped 토큰을 발급한다.
     * <ul>
     *   <li>{@code sub} = sellerInfoId (대신 행동되는 주체 = 테넌트)</li>
     *   <li>{@code act.sub} = memberId (실제 행위자 체인)</li>
     *   <li>{@code aud} = audience (대상 서비스, 예 "openat-product")</li>
     *   <li>{@code scope} = 허용 범위 (예 "product:write")</li>
     *   <li>{@code typ} = "scoped" (access 토큰으로 재사용 불가)</li>
     *   <li>만료: {@link JwtProperties#scopedTokenExpireSeconds()} (기본 120초)</li>
     * </ul>
     */
    public String createDelegationToken(UUID sellerInfoId, UUID memberId, String audience, String scope) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(jwtProperties.keyId()).and()
                .issuer(jwtProperties.issuer())
                .subject(sellerInfoId.toString())
                .claim(CLAIM_ACT, Map.of("sub", memberId.toString()))
                .audience().add(audience).and()
                .claim(CLAIM_SCOPE, scope)
                .claim(CLAIM_TYPE, TYPE_SCOPED)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(jwtProperties.scopedTokenExpireSeconds())))
                .signWith(rsaPrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * access 토큰의 서명/만료를 검증하고 클레임을 반환한다.
     * {@code typ}이 "access"가 아니면 {@code IllegalArgumentException}을 던진다.
     * 서명/만료 오류는 {@link io.jsonwebtoken.JwtException}(unchecked)으로 전파된다.
     */
    public Claims parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(rsaPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new IllegalArgumentException("access 토큰이 아닙니다.");
        }
        return claims;
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

    public long getScopedTokenExpireSeconds() {
        return jwtProperties.scopedTokenExpireSeconds();
    }
}
