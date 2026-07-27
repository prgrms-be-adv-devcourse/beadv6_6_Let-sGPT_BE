package com.openat.recommendation.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.application.service.RecommendationResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RecommendationResultCache {

  private static final Logger log = LoggerFactory.getLogger(RecommendationResultCache.class);
  private static final Duration TTL = Duration.ofHours(12);

  private final StringRedisTemplate redisTemplate;
  private final JsonRedisStore jsonRedisStore;

  public RecommendationResultCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.jsonRedisStore = new JsonRedisStore(redisTemplate, objectMapper);
  }

  public Optional<RecommendationResponse> find(String key) {
    return jsonRedisStore.read(key, RecommendationResponse.class);
  }

  public void save(String key, RecommendationResponse response) {
    jsonRedisStore.write(key, response, TTL);
  }

  public void invalidateMember(UUID memberId) {
    try {
      redisTemplate.delete(homeKey(memberId));
    } catch (Exception exception) {
      log.warn(
          "Failed to invalidate recommendation result cache: memberId={}", memberId, exception);
    }
  }

  public String cacheKey(UUID productId, UUID memberId) {
    return productId == null ? homeKey(memberId) : detailKey(productId);
  }

  private static String homeKey(UUID memberId) {
    return "rec:" + memberId + ":home";
  }

  private static String detailKey(UUID productId) {
    return "rec:detail:" + productId;
  }
}
