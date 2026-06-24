package com.openat.member.domain.repository;

import java.time.Duration;
import java.util.UUID;

public interface RefreshTokenRepository {

    void save(UUID memberId, String tokenId, Duration ttl);

    boolean isValid(UUID memberId, String tokenId);

    void delete(UUID memberId);
}
