package com.openat.member.domain.repository;

import java.time.Duration;
import java.util.UUID;

/**
 * 리프레시 토큰의 "현재 유효한 토큰 식별자(jti)"를 저장/조회하는 도메인 포트.
 * 토큰 자체(서명된 JWT 문자열)는 저장하지 않고 jti만 비교한다 — 재발급(rotation) 시
 * 이전 jti를 덮어쓰면 자동으로 이전 리프레시 토큰이 무효화된다.
 * 구현체는 {@code infrastructure.persistence.RefreshTokenRedisRepositoryAdaptor} 참고.
 */
public interface RefreshTokenRepository {

    void save(UUID memberId, String tokenId, Duration ttl);

    boolean isValid(UUID memberId, String tokenId);

    void delete(UUID memberId);
}
