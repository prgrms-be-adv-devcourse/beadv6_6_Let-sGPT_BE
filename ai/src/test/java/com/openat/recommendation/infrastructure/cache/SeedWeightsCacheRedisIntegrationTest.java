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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    long generationAtStart = cache.generation(memberId);

    assertThat(cache.saveIfUnchanged(memberId, null, first, ttl, generationAtStart)).isTrue();
    CachedWeights snapshot = cache.findSnapshot(memberId).orElseThrow();
    String key = "weights:" + memberId;
    template.opsForValue().set(key, "changed-by-concurrent-writer", ttl);

    assertThat(
            cache.saveIfUnchanged(
                memberId, snapshot.serialized(), first, ttl, generationAtStart))
        .isFalse();
    assertThat(template.opsForValue().get(key)).isEqualTo("changed-by-concurrent-writer");
    assertThat(template.getExpire(key)).isPositive().isLessThanOrEqualTo(ttl.getSeconds());
  }

  @Test
  void saveIfUnchanged_allowsOnlyOneOfTwoConcurrentWriters() throws Exception {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
    template.afterPropertiesSet();
    SeedWeightsCache cache = new SeedWeightsCache(template, new JacksonConfig().objectMapper());
    UUID memberId = UUID.randomUUID();
    Duration ttl = Duration.ofMinutes(10);
    cache.save(memberId, SeedWeights.full(List.of(), Instant.now()), ttl);
    CachedWeights snapshot = cache.findSnapshot(memberId).orElseThrow();
    SeedWeights first = SeedWeights.partial(List.of(new Seed(UUID.randomUUID(), 0.5, true)), Instant.now());
    SeedWeights second = SeedWeights.partial(List.of(new Seed(UUID.randomUUID(), 0.3, false)), Instant.now());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<Boolean> firstWrite =
          CompletableFuture.supplyAsync(
              () -> saveAfterStart(cache, memberId, snapshot, first, ttl, start), executor);
      CompletableFuture<Boolean> secondWrite =
          CompletableFuture.supplyAsync(
              () -> saveAfterStart(cache, memberId, snapshot, second, ttl, start), executor);
      start.countDown();

      assertThat(List.of(firstWrite.get(), secondWrite.get())).containsExactlyInAnyOrder(true, false);
      assertThat(cache.find(memberId).orElseThrow().seeds()).isIn(first.seeds(), second.seeds());
    } finally {
      executor.shutdownNow();
    }
  }

  private boolean saveAfterStart(
      SeedWeightsCache cache,
      UUID memberId,
      CachedWeights snapshot,
      SeedWeights weights,
      Duration ttl,
      CountDownLatch start) {
    try {
      start.await();
      return cache.saveIfUnchanged(
          memberId, snapshot.serialized(), weights, ttl, snapshot.generation());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }
}
