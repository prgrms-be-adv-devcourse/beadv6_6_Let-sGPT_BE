package com.openat.recommendation.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.application.service.RecommendationResponse;
import com.openat.recommendation.infrastructure.config.JacksonConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
  void saveAndFind_roundTripsProductsIncludingDropId() throws Exception {
    String key = "rec:detail:product";
    RecommendationResponse response =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "연관",
                    List.of(
                        new RecommendationResponse.Product(
                            UUID.randomUUID(), UUID.randomUUID(), "드롭 상품", "판매자", 900L, "thumb"),
                        new RecommendationResponse.Product(
                            UUID.randomUUID(), null, "일반 상품", "판매자", 800L, "thumb")))));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(objectMapper.writeValueAsString(response));

    assertThat(new RecommendationResultCache(redisTemplate, objectMapper).find(key))
        .contains(response);
  }

  /**
   * dropId 추가 이전에 쓰인 캐시 엔트리(TTL 12시간)는 배포 직후에도 그대로 남아 있다. 역직렬화가
   * 깨지면 모든 히트가 미스로 떨어져 전면 LLM 폭주가 되므로, 옛 JSON이 그대로 읽히는지 못 박는다.
   */
  @Test
  void find_whenCachedJsonPredatesDropIdField_parsesWithNullDropId() {
    String key = "rec:member:home";
    UUID productId = UUID.randomUUID();
    String legacyJson =
        """
        {"sections":[{"title":"이런 드롭은 어떠세요?","products":[
          {"productId":"%s","name":"옛 상품","sellerName":"판매자","price":1000,
           "thumbnailUrl":"thumb"}]}]}
        """
            .formatted(productId);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(legacyJson);

    Optional<RecommendationResponse> found =
        new RecommendationResultCache(redisTemplate, objectMapper).find(key);

    assertThat(found).isPresent();
    assertThat(found.get().sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.products())
                    .singleElement()
                    .satisfies(
                        product -> {
                          assertThat(product.productId()).isEqualTo(productId);
                          assertThat(product.dropId()).isNull();
                          assertThat(product.name()).isEqualTo("옛 상품");
                          assertThat(product.price()).isEqualTo(1000L);
                        }));
  }

  /**
   * 캐시 JSON의 필드명은 계약이다. {@code thumbnailUrl}은 값이 실제로는 오브젝트 키지만, 이름을
   * 바꾸면 살아 있는 캐시(TTL 12시간)가 전부 미스로 떨어져 LLM 비용이 폭증한다. 이름을 못 박는다.
   */
  @Test
  void save_writesThumbnailKeyUnderThumbnailUrlProperty() throws Exception {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    RecommendationResponse response =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "연관",
                    List.of(
                        new RecommendationResponse.Product(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "드롭 상품",
                            "판매자",
                            900L,
                            "products/2026/07/abc.jpg")))));

    new RecommendationResultCache(redisTemplate, objectMapper).save("rec:detail:product", response);

    verify(valueOperations)
        .set(
            eq("rec:detail:product"),
            contains("\"thumbnailUrl\":\"products/2026/07/abc.jpg\""),
            eq(Duration.ofHours(12)));
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
