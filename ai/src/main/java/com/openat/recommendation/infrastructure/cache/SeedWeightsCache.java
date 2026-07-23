package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.domain.model.Seed;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeedWeightsCache {

  private static final Logger log = LoggerFactory.getLogger(SeedWeightsCache.class);
  public static final Duration FULL_TTL = Duration.ofHours(24);
  private static final TypeReference<List<Seed>> SEED_LIST = new TypeReference<>() {};

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  public SeedWeightsCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  public Optional<List<Seed>> find(UUID memberId) {
    try {
      String value = redisTemplate.opsForValue().get(key(memberId));
      return value == null
          ? Optional.empty()
          : Optional.of(objectMapper.readValue(value, SEED_LIST));
    } catch (Exception exception) {
      log.warn(
          "Failed to read seed weights cache; treating as miss: memberId={}", memberId, exception);
      return Optional.empty();
    }
  }

  public void save(UUID memberId, List<Seed> seeds, Duration ttl) {
    String key = key(memberId);
    try {
      redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(seeds), ttl);
    } catch (Exception exception) {
      log.warn("Failed to save seed weights cache: memberId={}", memberId, exception);
    }
  }

  private String key(UUID memberId) {
    return "weights:" + memberId;
  }
}
