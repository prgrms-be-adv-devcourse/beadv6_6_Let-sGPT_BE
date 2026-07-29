package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.domain.model.Seed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeedWeightsCache {

  public static final Duration FULL_TTL = Duration.ofHours(24);
  private static final Logger log = LoggerFactory.getLogger(SeedWeightsCache.class);
  private static final TypeReference<List<Seed>> SEED_LIST = new TypeReference<>() {};

  private final JsonRedisStore jsonRedisStore;
  private final ObjectMapper objectMapper;

  public SeedWeightsCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.jsonRedisStore = new JsonRedisStore(redisTemplate, objectMapper);
    this.objectMapper = objectMapper;
  }

  public Optional<SeedWeights> find(UUID memberId) {
    return findSnapshot(memberId).map(CachedWeights::weights);
  }

  /** 원자적 부분 갱신을 위해, 역직렬화 결과와 Redis 원문을 함께 읽는다. */
  public Optional<CachedWeights> findSnapshot(UUID memberId) {
    String key = key(memberId);
    return jsonRedisStore
        .readRaw(key)
        .flatMap(
            serialized -> {
              try {
                return toSeedWeights(objectMapper.readTree(serialized))
                    .map(
                        weights ->
                            new CachedWeights(weights, serialized, jsonRedisStore.generation(key)));
              } catch (Exception exception) {
                log.warn("Failed to parse cached seed weights; treating as miss", exception);
                return Optional.empty();
              }
            });
  }

  public void save(UUID memberId, SeedWeights weights, Duration ttl) {
    jsonRedisStore.writeFull(key(memberId), weights, ttl);
  }

  /** 읽은 캐시가 그대로일 때만 저장한다. 완전 결과가 먼저 들어왔으면 false로 알려 준다. */
  public boolean saveIfUnchanged(
      UUID memberId,
      String expectedSerialized,
      SeedWeights weights,
      Duration ttl,
      long generationAtStart) {
    return jsonRedisStore.writeIfUnchanged(
        key(memberId), expectedSerialized, weights, ttl, generationAtStart);
  }

  private Optional<SeedWeights> toSeedWeights(JsonNode node) {
    try {
      return Optional.of(
          node.isArray()
              ? SeedWeights.unknownOrigin(objectMapper.convertValue(node, SEED_LIST))
              : objectMapper.convertValue(node, SeedWeights.class));
    } catch (Exception exception) {
      log.warn("Failed to map cached seed weights; treating as miss", exception);
      return Optional.empty();
    }
  }

  private String key(UUID memberId) {
    return "weights:" + memberId;
  }

  public record CachedWeights(SeedWeights weights, String serialized, long generation) {}

  public long generation(UUID memberId) {
    return jsonRedisStore.generation(key(memberId));
  }

  /** {@code collectedAt}은 담긴 가장 오래된 시드를 모은 시각 — 살려 오면 물려받아 수명이 늘지 않는다. */
  public record SeedWeights(List<Seed> seeds, boolean complete, Instant collectedAt) {

    public SeedWeights {
      seeds = seeds == null ? List.of() : List.copyOf(seeds);
      collectedAt = collectedAt == null ? Instant.EPOCH : collectedAt;
    }

    public static SeedWeights full(List<Seed> seeds, Instant collectedAt) {
      return new SeedWeights(seeds, true, collectedAt);
    }

    public static SeedWeights partial(List<Seed> seeds, Instant collectedAt) {
      return new SeedWeights(seeds, false, collectedAt);
    }

    /** 메타데이터가 없던 구버전 엔트리. 불완전·무한히 오래된 것으로 보아 salvage 재료로 쓰지 않는다. */
    public static SeedWeights unknownOrigin(List<Seed> seeds) {
      return new SeedWeights(seeds, false, Instant.EPOCH);
    }
  }
}
