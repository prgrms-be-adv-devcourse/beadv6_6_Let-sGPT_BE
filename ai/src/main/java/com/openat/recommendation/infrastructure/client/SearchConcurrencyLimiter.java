package com.openat.recommendation.infrastructure.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis ZSET 슬라이딩 윈도우로 검색 호출 동시성을 클러스터 전역으로 제한한다.
 *
 * <p>permit을 시각 점수로 기록해 두면 acquire 시도마다 TTL이 지난 permit이 먼저 청소된다. permit은 release가 호출되거나 인스턴스가 죽어 갱신
 * 스레드가 끊길 때까지 유지된다.
 */
class SearchConcurrencyLimiter {

  private static final Logger log = LoggerFactory.getLogger(SearchConcurrencyLimiter.class);
  private static final String PERMITS_KEY = "recommendation:search:permits";
  private static final long INITIAL_POLL_DELAY_MILLIS = 15L;
  private static final long MAX_POLL_DELAY_MILLIS = 200L;
  private static final double POLL_BACKOFF_MULTIPLIER = 2.0;
  private static final double POLL_JITTER_RATIO = 0.2;
  private static final long RENEWAL_JOIN_TIMEOUT_MILLIS = 1000L;

  private final StringRedisTemplate redisTemplate;
  private final int maxConcurrency;
  private final long permitTtlMillis;
  private final long acquireTimeoutMillis;
  private final long renewalIntervalMillis;
  private final ConcurrentHashMap<String, PermitLease> permitLeases = new ConcurrentHashMap<>();

  private final RedisScript<Long> acquireScript =
      RedisScript.of(new ClassPathResource("redis/acquire-search-permit.lua"), Long.class);
  private final RedisScript<Long> releaseScript =
      RedisScript.of(new ClassPathResource("redis/release-search-permit.lua"), Long.class);
  private final RedisScript<Long> renewScript =
      RedisScript.of(new ClassPathResource("redis/renew-search-permit.lua"), Long.class);

  SearchConcurrencyLimiter(
      StringRedisTemplate redisTemplate,
      int maxConcurrency,
      Duration permitTtl,
      Duration acquireTimeout) {
    this.redisTemplate = redisTemplate;
    this.maxConcurrency = Math.max(1, maxConcurrency);
    this.permitTtlMillis = Math.max(1, permitTtl.toMillis());
    this.acquireTimeoutMillis = Math.max(0, acquireTimeout.toMillis());
    this.renewalIntervalMillis = Math.max(1, this.permitTtlMillis / 3);
  }

  boolean tryAcquire(String permitId) {
    long deadline = System.currentTimeMillis() + acquireTimeoutMillis;
    long pollDelay = INITIAL_POLL_DELAY_MILLIS;
    while (true) {
      if (acquireOnce(permitId)) {
        startRenewal(permitId);
        return true;
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return false;
      }
      try {
        Thread.sleep(Math.min(jitter(pollDelay), remaining));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
      pollDelay = Math.min((long) (pollDelay * POLL_BACKOFF_MULTIPLIER), MAX_POLL_DELAY_MILLIS);
    }
  }

  void release(String permitId) {
    stopRenewal(permitId);
    redisTemplate.execute(releaseScript, List.of(PERMITS_KEY), permitId);
  }

  private long jitter(long baseDelayMillis) {
    double factor =
        1 + ThreadLocalRandom.current().nextDouble(-POLL_JITTER_RATIO, POLL_JITTER_RATIO);
    return Math.max(1L, Math.round(baseDelayMillis * factor));
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

  private void startRenewal(String permitId) {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    Thread renewer = Thread.ofVirtual().unstarted(() -> renewLoop(permitId, cancelled));
    permitLeases.put(permitId, new PermitLease(renewer, cancelled));
    renewer.start();
  }

  private void renewLoop(String permitId, AtomicBoolean cancelled) {
    try {
      while (!cancelled.get()) {
        Thread.sleep(renewalIntervalMillis);
        try {
          Long renewed =
              redisTemplate.execute(
                  renewScript,
                  List.of(PERMITS_KEY),
                  Long.toString(System.currentTimeMillis()),
                  Long.toString(permitTtlMillis),
                  permitId);
          if (!Long.valueOf(1L).equals(renewed)) {
            log.warn("search concurrency permit {} lease already lost before renewal", permitId);
            return;
          }
        } catch (RuntimeException exception) {
          log.warn("failed to renew search concurrency permit {}", permitId, exception);
        }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private void stopRenewal(String permitId) {
    PermitLease lease = permitLeases.remove(permitId);
    if (lease == null) {
      return;
    }
    // interrupt보다 먼저 세워야 broad catch가 삼켜도 다음 순회에서 멈춘다.
    lease.cancelled().set(true);
    lease.renewer().interrupt();
    try {
      // join 없이 지우면 release 직후 갱신 스레드가 permit을 되살리는 좀비 permit이 생길 수 있다.
      lease.renewer().join(RENEWAL_JOIN_TIMEOUT_MILLIS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private record PermitLease(Thread renewer, AtomicBoolean cancelled) {}
}
