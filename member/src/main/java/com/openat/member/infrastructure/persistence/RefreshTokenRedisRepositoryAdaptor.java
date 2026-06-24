package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.repository.RefreshTokenRepository;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRedisRepositoryAdaptor implements RefreshTokenRepository {

    private static final String KEY_PREFIX = "refresh-token:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(UUID memberId, String tokenId, Duration ttl) {
        redisTemplate.opsForValue().set(key(memberId), tokenId, ttl);
    }

    @Override
    public boolean isValid(UUID memberId, String tokenId) {
        String savedTokenId = redisTemplate.opsForValue().get(key(memberId));
        return Objects.equals(savedTokenId, tokenId);
    }

    @Override
    public void delete(UUID memberId) {
        redisTemplate.delete(key(memberId));
    }

    private String key(UUID memberId) {
        return KEY_PREFIX + memberId;
    }
}
