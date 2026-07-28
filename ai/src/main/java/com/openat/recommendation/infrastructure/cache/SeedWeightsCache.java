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
    return jsonRedisStore.read(key(memberId), JsonNode.class).flatMap(this::toSeedWeights);
  }

  public void save(UUID memberId, SeedWeights weights, Duration ttl) {
    jsonRedisStore.write(key(memberId), weights, ttl);
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

  /**
   * 가중치 캐시 엔트리. {@code complete}는 이 엔트리를 만들 때 모든 신호 조회가 성공했는지,
   * {@code collectedAt}은 엔트리에 담긴 가장 오래된 시드를 언제 모았는지다. 한쪽 신호가 실패해
   * 기존 엔트리에서 절반을 살려 오면 그 시각을 물려받으므로, 부분 저장이 반복돼도 시드의 실제
   * 수명은 늘어나지 않는다.
   */
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

    /**
     * 메타데이터가 없던 구버전 엔트리(시드 배열만 저장). 완전성과 수집 시각을 알 수 없으므로
     * 가장 보수적으로 읽는다 — 불완전하고 무한히 오래된 것으로 본다. 시드 자체는 그대로 쓰되
     * salvage 재료나 덮어쓰기 보호 대상으로는 쓰지 않는다.
     */
    public static SeedWeights unknownOrigin(List<Seed> seeds) {
      return new SeedWeights(seeds, false, Instant.EPOCH);
    }
  }
}
