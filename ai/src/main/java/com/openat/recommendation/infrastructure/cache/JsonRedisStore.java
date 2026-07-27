package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

final class JsonRedisStore {

  private static final Logger log = LoggerFactory.getLogger(JsonRedisStore.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  JsonRedisStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  <T> Optional<T> read(String key, Class<T> type) {
    try {
      String value = redisTemplate.opsForValue().get(key);
      return value == null ? Optional.empty() : Optional.of(objectMapper.readValue(value, type));
    } catch (Exception exception) {
      log.warn("Failed to read JSON Redis value; treating as miss: key={}", key, exception);
      return Optional.empty();
    }
  }

  <T> Optional<T> read(String key, TypeReference<T> type) {
    try {
      String value = redisTemplate.opsForValue().get(key);
      return value == null ? Optional.empty() : Optional.of(objectMapper.readValue(value, type));
    } catch (Exception exception) {
      log.warn("Failed to read JSON Redis value; treating as miss: key={}", key, exception);
      return Optional.empty();
    }
  }

  void write(String key, Object value, Duration ttl) {
    try {
      redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
    } catch (Exception exception) {
      log.warn("Failed to write JSON Redis value: key={}", key, exception);
    }
  }
}
