package com.openat.recommendation.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache.SeedWeights;
import com.openat.recommendation.infrastructure.config.JacksonConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SeedWeightsCacheTest {

  @Mock StringRedisTemplate redisTemplate;
  @Mock ValueOperations<String, String> valueOperations;
  private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

  @Test
  void saveAndFind_roundTripsJson() throws Exception {
    UUID memberId = UUID.randomUUID();
    SeedWeights weights =
        SeedWeights.full(
            List.of(new Seed(UUID.randomUUID(), 0.5, true)),
            Instant.now().truncatedTo(ChronoUnit.MILLIS));
    String key = "weights:" + memberId;
    String json = objectMapper.writeValueAsString(weights);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(json);
    SeedWeightsCache cache = new SeedWeightsCache(redisTemplate, objectMapper);

    cache.save(memberId, weights, SeedWeightsCache.FULL_TTL);
    assertThat(cache.find(memberId)).contains(weights);
  }

  @Test
  @DisplayName("메타데이터 없는 구버전 엔트리도 읽히고, 불완전·최고령으로 취급된다")
  void find_whenCachedEntryIsLegacySeedArray_readsSeedsAsIncompleteAndOldest() throws Exception {
    UUID memberId = UUID.randomUUID();
    List<Seed> seeds = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("weights:" + memberId))
        .thenReturn(objectMapper.writeValueAsString(seeds));

    SeedWeights found =
        new SeedWeightsCache(redisTemplate, objectMapper).find(memberId).orElseThrow();

    assertThat(found.seeds()).isEqualTo(seeds);
    assertThat(found.complete()).isFalse();
    assertThat(found.collectedAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  @DisplayName("엔트리에 수집 시각이 없으면 최고령으로 읽는다")
  void find_whenCollectedAtIsMissing_defaultsToOldest() {
    UUID memberId = UUID.randomUUID();
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("weights:" + memberId)).thenReturn("{\"seeds\":[],\"complete\":true}");

    SeedWeights found =
        new SeedWeightsCache(redisTemplate, objectMapper).find(memberId).orElseThrow();

    assertThat(found.collectedAt()).isEqualTo(Instant.EPOCH);
    assertThat(found.seeds()).isEmpty();
  }

  @Test
  void find_whenRedisFails_returnsMiss() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis"));

    assertThat(new SeedWeightsCache(redisTemplate, objectMapper).find(UUID.randomUUID())).isEmpty();
  }

  @Test
  void save_whenRedisFails_doesNotThrow() {
    SeedWeightsCache cache = new SeedWeightsCache(redisTemplate, objectMapper);

    assertThatCode(
            () ->
                cache.save(
                    UUID.randomUUID(),
                    SeedWeights.full(
                        List.of(new Seed(UUID.randomUUID(), 0.5, true)), Instant.now()),
                    SeedWeightsCache.FULL_TTL))
        .doesNotThrowAnyException();
  }

  @Test
  void find_whenCachedJsonIsInvalid_returnsMiss() {
    UUID memberId = UUID.randomUUID();
    String key = "weights:" + memberId;
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn("not-a-json");
    SeedWeightsCache cache = new SeedWeightsCache(redisTemplate, objectMapper);

    assertThat(cache.find(memberId)).isEmpty();
  }

  @Test
  @DisplayName("시드 값이 도메인 규칙을 어기면 미스로 처리한다")
  void find_whenCachedSeedIsInvalid_returnsMiss() {
    UUID memberId = UUID.randomUUID();
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("weights:" + memberId))
        .thenReturn(
            "{\"seeds\":[{\"productId\":\""
                + UUID.randomUUID()
                + "\",\"score\":9.0,\"buy\":true}],\"complete\":true,"
                + "\"collectedAt\":\"2026-01-01T00:00:00Z\"}");

    assertThat(new SeedWeightsCache(redisTemplate, objectMapper).find(memberId)).isEmpty();
  }
}
