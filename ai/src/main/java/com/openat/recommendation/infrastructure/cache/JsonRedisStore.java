package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

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

  Optional<String> readRaw(String key) {
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    } catch (Exception exception) {
      log.warn("Failed to read JSON Redis value; treating as miss: key={}", key, exception);
      return Optional.empty();
    }
  }

  long generation(String key) {
    try {
      String generation = redisTemplate.opsForValue().get(key + ":generation");
      return generation == null ? 0L : Long.parseLong(generation);
    } catch (Exception exception) {
      log.warn("Failed to read JSON Redis generation; treating as zero: key={}", key, exception);
      return 0L;
    }
  }

  void writeFull(String key, Object value, Duration ttl) {
    try {
      DefaultRedisScript<Long> script = new DefaultRedisScript<>();
      script.setResultType(Long.class);
      script.setScriptText(
          "local generation = redis.call('INCR', KEYS[2]) "
              + "redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1]) "
              + "redis.call('PEXPIRE', KEYS[2], ARGV[2]) "
              + "return generation");
      redisTemplate.execute(
          script,
          List.of(key, key + ":generation"),
          objectMapper.writeValueAsString(value),
          Long.toString(ttl.toMillis()));
    } catch (Exception exception) {
      log.warn("Failed to write full JSON Redis value: key={}", key, exception);
    }
  }

  /** 읽은 직후 갱신된 캐시를 덮지 않는 Redis CAS. {@code expected}가 null이면 키가 없을 때만 쓴다. */
  boolean writeIfUnchanged(
      String key, String expected, Object value, Duration ttl, long generationAtStart) {
    try {
      String serialized = objectMapper.writeValueAsString(value);
      DefaultRedisScript<Long> script = new DefaultRedisScript<>();
      script.setResultType(Long.class);
      script.setScriptText(
          "local current = redis.call('GET', KEYS[1]) "
              + "local generation = tonumber(redis.call('GET', KEYS[2]) or '0') "
              + "if generation > tonumber(ARGV[4]) then return 0 end "
              + "if ARGV[1] == '__ABSENT__' then "
              + "  if current then return 0 end "
              + "elseif current ~= ARGV[1] then return 0 end "
              + "redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2]) "
              + "return 1");
      Long written =
          redisTemplate.execute(
              script,
              List.of(key, key + ":generation"),
              expected == null ? "__ABSENT__" : expected,
              serialized,
              Long.toString(ttl.toMillis()),
              Long.toString(generationAtStart));
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
