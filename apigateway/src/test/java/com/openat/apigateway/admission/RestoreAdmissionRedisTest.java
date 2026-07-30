package com.openat.apigateway.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code restore-admission.lua}가 GIVE_UP tombstone을 실제로 존중하는지 실제 Redis로
 * 검증한다. queue의 {@code WaitingQueueRedisRepositoryTest}가 "release-admission.lua가
 * tombstone을 남긴다"를 검증하고, 이 테스트가 "restore-admission.lua가 그 tombstone을
 * 존중한다"를 검증한다 - 두 테스트가 합쳐져야 GETDEL → GIVE_UP → 5xx → restore 순서의
 * 동시성 계약 전체가 고정된다(각 모듈이 상대 모듈의 Lua 파일을 직접 참조하지 않는 관례상
 * 계약을 양쪽에서 나눠 검증한다).
 *
 * <p>리뷰 지적 배경: 사용자가 GETDEL 이후(주문 진행 중) GIVE_UP하면 release-admission.lua는
 * admission 키가 이미 없어(DEL==0) outstanding을 건드리지 않고 tombstone만 남긴다. 그 상태에서
 * 그 주문이 다운스트림 5xx로 실패하면, 이 스크립트가 tombstone 없이 admitted ZSET만 보고
 * 입장권을 되살렸다 - 포기했던 사용자가 다시 READY로 보이는 lost-update였다.
 */
@Testcontainers
@DisplayName("restore-admission.lua - GIVE_UP tombstone 존중 여부")
class RestoreAdmissionRedisTest {

  @Container
  static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static LettuceConnectionFactory connectionFactory;
  static StringRedisTemplate redisTemplate;
  static ReactiveStringRedisTemplate reactiveRedisTemplate;
  static RedisScript<Long> restoreAdmissionScript;

  private static final String DROP_ID = "drop-1";
  private static final String USER_ID = "user-1";
  private static final String ADMITTED_KEY = "admitted:" + DROP_ID;
  private static final String ADMISSION_KEY = "admission:" + DROP_ID + ":" + USER_ID;
  private static final String TOMBSTONE_KEY = "giveup:" + DROP_ID + ":" + USER_ID;

  @BeforeAll
  static void init() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    reactiveRedisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
    restoreAdmissionScript =
        RedisScript.of(new ClassPathResource("redis/restore-admission.lua"), Long.class);
  }

  @AfterAll
  static void cleanup() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void flush() {
    redisTemplate.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  @Test
  @DisplayName(
      "GETDEL 이후 GIVE_UP으로 tombstone이 남은 상태면, admitted ZSET에 여유가 있어도 복구하지 않는다")
  void restoreAdmission_withTombstone_neverRestores() {
    // given: GETDEL로 admission 키가 이미 소진됐고(이 테스트에선 애초에 안 만듦),
    // admitted ZSET엔 아직 남아있다(스위퍼가 회수하기 전) - 정상적이라면 복구 조건을 만족한다.
    long expiresAt = Instant.now().toEpochMilli() + 60_000;
    redisTemplate.opsForZSet().add(ADMITTED_KEY, USER_ID, expiresAt);
    // 그런데 그 사이 GIVE_UP이 release-admission.lua를 통해 tombstone을 남겼다.
    redisTemplate.opsForValue().set(TOMBSTONE_KEY, "1");

    Long restored = executeRestore();

    assertThat(restored).isZero();
    assertThat(redisTemplate.hasKey(ADMISSION_KEY)).isFalse();
  }

  @Test
  @DisplayName("tombstone이 없고 admitted ZSET에 여유가 있으면 정상적으로 복구한다(회귀 방지)")
  void restoreAdmission_withoutTombstone_restoresNormally() {
    long expiresAt = Instant.now().toEpochMilli() + 60_000;
    redisTemplate.opsForZSet().add(ADMITTED_KEY, USER_ID, expiresAt);

    Long restored = executeRestore();

    assertThat(restored).isEqualTo(1L);
    assertThat(redisTemplate.opsForValue().get(ADMISSION_KEY)).isEqualTo("3");
  }

  @Test
  @DisplayName("tombstone이 있어도 admitted ZSET에서 이미 회수됐으면(스위퍼가 먼저 처리) 어차피 복구하지 않는다")
  void restoreAdmission_withTombstoneAndAlreadyReclaimed_isNoOp() {
    // admitted ZSET에 아예 없음(스위퍼가 이미 회수) - tombstone 유무와 무관하게 0이어야 한다.
    redisTemplate.opsForValue().set(TOMBSTONE_KEY, "1");

    Long restored = executeRestore();

    assertThat(restored).isZero();
  }

  private Long executeRestore() {
    return reactiveRedisTemplate
        .execute(
            restoreAdmissionScript,
            List.of(ADMITTED_KEY, ADMISSION_KEY, TOMBSTONE_KEY),
            List.of(USER_ID, "3", String.valueOf(Instant.now().toEpochMilli())))
        .blockFirst();
  }
}
