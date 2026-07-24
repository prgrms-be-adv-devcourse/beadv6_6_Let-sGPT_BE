package com.openat.recommendation.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.infrastructure.config.JacksonConfig;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
  void saveAndFind_roundTripsJsonAndSetsTwentyFourHourTtl() throws Exception {
    UUID memberId = UUID.randomUUID();
    List<Seed> seeds = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    String key = "weights:" + memberId;
    String json = objectMapper.writeValueAsString(seeds);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(json);
    SeedWeightsCache cache = new SeedWeightsCache(redisTemplate, objectMapper);

    cache.save(memberId, seeds, SeedWeightsCache.FULL_TTL);

    verify(valueOperations).set(key, json, Duration.ofHours(24));
    assertThat(cache.find(memberId)).contains(seeds);
  }

  @Test
  void find_whenRedisFails_returnsMiss() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis"));

    assertThat(new SeedWeightsCache(redisTemplate, objectMapper).find(UUID.randomUUID())).isEmpty();
  }

  @Test
  void save_whenRedisFails_doesNotThrow() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis"));
    SeedWeightsCache cache = new SeedWeightsCache(redisTemplate, objectMapper);

    assertThatCode(
            () ->
                cache.save(
                    UUID.randomUUID(),
                    List.of(new Seed(UUID.randomUUID(), 0.5, true)),
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
}
