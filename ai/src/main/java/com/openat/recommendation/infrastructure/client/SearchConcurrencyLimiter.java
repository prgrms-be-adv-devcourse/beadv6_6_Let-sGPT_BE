package com.openat.recommendation.infrastructure.client;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis ZSET 슬라이딩 윈도우로 검색 호출 동시성을 클러스터 전역으로 제한한다.
 *
 * <p>permit을 시각 점수로 기록해 두면 acquire 시도마다 TTL이 지난 permit이 먼저 청소된다. 인스턴스가 release 없이 죽어도 permit TTL이
 * 지나면 다음 acquire에서 자동 회수되므로, 이 TTL은 "이 호출이 최대 이만큼 걸릴 수 있다"는 상한이지 대기자가 기다리는 acquire-timeout과는 별개
 * 값이다.
 */
class SearchConcurrencyLimiter {

  private static final String PERMITS_KEY = "recommendation:search:permits";
  private static final long POLL_INTERVAL_MILLIS = 30L;

  private final StringRedisTemplate redisTemplate;
  private final int maxConcurrency;
  private final long permitTtlMillis;
  private final long acquireTimeoutMillis;

  private final RedisScript<Long> acquireScript =
      RedisScript.of(new ClassPathResource("redis/acquire-search-permit.lua"), Long.class);
  private final RedisScript<Long> releaseScript =
      RedisScript.of(new ClassPathResource("redis/release-search-permit.lua"), Long.class);

  SearchConcurrencyLimiter(
      StringRedisTemplate redisTemplate,
      int maxConcurrency,
      Duration permitTtl,
      Duration acquireTimeout) {
    this.redisTemplate = redisTemplate;
    this.maxConcurrency = Math.max(1, maxConcurrency);
    this.permitTtlMillis = Math.max(1, permitTtl.toMillis());
    this.acquireTimeoutMillis = Math.max(0, acquireTimeout.toMillis());
  }

  boolean tryAcquire(String permitId) {
    long deadline = System.currentTimeMillis() + acquireTimeoutMillis;
    while (true) {
      if (acquireOnce(permitId)) {
        return true;
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return false;
      }
      try {
        Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remaining));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  void release(String permitId) {
    redisTemplate.execute(releaseScript, List.of(PERMITS_KEY), permitId);
  }

  private boolean acquireOnce(String permitId) {
    Long acquired =
        redisTemplate.execute(
            acquireScript,
            List.of(PERMITS_KEY),
            Long.toString(System.currentTimeMillis()),
            Long.toString(permitTtlMillis),
            Integer.toString(maxConcurrency),
            permitId);
    return Long.valueOf(1L).equals(acquired);
  }
}
