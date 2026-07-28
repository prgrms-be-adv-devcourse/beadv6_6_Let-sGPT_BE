package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

  Optional<String> readRaw(String key) {
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    } catch (Exception exception) {
      log.warn("Failed to read JSON Redis value; treating as miss: key={}", key, exception);
      return Optional.empty();
    }
  }

  /**
   * 읽은 직후 다른 요청이 갱신한 캐시를 덮지 않는 Redis CAS. {@code expected}가 null이면 키가
   * 아직 없는 경우에만 쓴다.
   */
  boolean writeIfUnchanged(String key, String expected, Object value, Duration ttl) {
    try {
      String serialized = objectMapper.writeValueAsString(value);
      DefaultRedisScript<Long> script = new DefaultRedisScript<>();
      script.setResultType(Long.class);
      script.setScriptText(
          "local current = redis.call('GET', KEYS[1]) "
              + "if ARGV[1] == '__ABSENT__' then "
              + "  if current then return 0 end "
              + "elseif current ~= ARGV[1] then return 0 end "
              + "redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2]) "
              + "return 1");
      Long written =
          redisTemplate.execute(
              script,
              List.of(key),
              expected == null ? "__ABSENT__" : expected,
              serialized,
              Long.toString(ttl.toMillis()));
      return Long.valueOf(1L).equals(written);
    } catch (Exception exception) {
      log.warn("Failed to conditionally write JSON Redis value: key={}", key, exception);
      return false;
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
