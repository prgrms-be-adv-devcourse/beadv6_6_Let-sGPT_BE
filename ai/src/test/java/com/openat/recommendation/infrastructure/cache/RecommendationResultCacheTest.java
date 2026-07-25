package com.openat.recommendation.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.application.service.RecommendationResponse;
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
class RecommendationResultCacheTest {

  @Mock StringRedisTemplate redisTemplate;
  @Mock ValueOperations<String, String> valueOperations;
  private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

  @Test
  void saveAndFind_roundTripsJsonAndSetsTwelveHourTtl() throws Exception {
    String key = "rec:member:home";
    RecommendationResponse response = new RecommendationResponse(List.of());
    String json = objectMapper.writeValueAsString(response);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(json);
    RecommendationResultCache cache = new RecommendationResultCache(redisTemplate, objectMapper);

    cache.save(key, response);

    verify(valueOperations).set(key, json, Duration.ofHours(12));
    assertThat(cache.find(key)).contains(response);
  }

  @Test
  void find_whenRedisFails_returnsMiss() {
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis"));

    assertThat(new RecommendationResultCache(redisTemplate, objectMapper).find("key")).isEmpty();
  }

  @Test
  void save_whenRedisFails_doesNotRethrow() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    doThrow(new RuntimeException("redis"))
        .when(valueOperations)
        .set(any(), any(), any(Duration.class));

    assertThatCode(
            () ->
                new RecommendationResultCache(redisTemplate, objectMapper)
                    .save("key", new RecommendationResponse(List.of())))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidateMember_deletesMemberHomeKey() {
    UUID memberId = UUID.randomUUID();

    new RecommendationResultCache(redisTemplate, objectMapper).invalidateMember(memberId);

    // 회원 캐시는 rec:{memberId}:home 단일 키 — 직접 삭제(SCAN 불필요)
    verify(redisTemplate).delete("rec:" + memberId + ":home");
  }
}
