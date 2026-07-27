package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.domain.model.Seed;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeedWeightsCache {

  public static final Duration FULL_TTL = Duration.ofHours(24);
  private static final TypeReference<List<Seed>> SEED_LIST = new TypeReference<>() {};

  private final JsonRedisStore jsonRedisStore;

  public SeedWeightsCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.jsonRedisStore = new JsonRedisStore(redisTemplate, objectMapper);
  }

  public Optional<List<Seed>> find(UUID memberId) {
    return jsonRedisStore.read(key(memberId), SEED_LIST);
  }

  public void save(UUID memberId, List<Seed> seeds, Duration ttl) {
    jsonRedisStore.write(key(memberId), seeds, ttl);
  }

  private String key(UUID memberId) {
    return "weights:" + memberId;
  }
}
