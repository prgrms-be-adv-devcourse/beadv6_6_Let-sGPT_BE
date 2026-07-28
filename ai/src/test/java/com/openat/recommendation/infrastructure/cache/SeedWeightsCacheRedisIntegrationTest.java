package com.openat.recommendation.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache.CachedWeights;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache.SeedWeights;
import com.openat.recommendation.infrastructure.config.JacksonConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class SeedWeightsCacheRedisIntegrationTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;

  @AfterAll
  static void closeConnectionFactory() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void saveIfUnchanged_writesOnlyForMatchingSnapshotAndSetsTtl() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    SeedWeightsCache cache = new SeedWeightsCache(template, new JacksonConfig().objectMapper());
    UUID memberId = UUID.randomUUID();
    Duration ttl = Duration.ofMinutes(10);
    SeedWeights first =
        SeedWeights.full(List.of(new Seed(UUID.randomUUID(), 0.5, true)), Instant.now());

    assertThat(cache.saveIfUnchanged(memberId, null, first, ttl)).isTrue();
    CachedWeights snapshot = cache.findSnapshot(memberId).orElseThrow();
    String key = "weights:" + memberId;
    template.opsForValue().set(key, "changed-by-concurrent-writer", ttl);

    assertThat(cache.saveIfUnchanged(memberId, snapshot.serialized(), first, ttl)).isFalse();
    assertThat(template.opsForValue().get(key)).isEqualTo("changed-by-concurrent-writer");
    assertThat(template.getExpire(key)).isPositive().isLessThanOrEqualTo(ttl.getSeconds());
  }
}
