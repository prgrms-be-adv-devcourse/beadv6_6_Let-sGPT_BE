package com.openat.drop.infrastructure.cache;

import com.openat.drop.domain.repository.DropRecoveryRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DropRecoveryRedisAdapter implements DropRecoveryRepository {

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<Long> beginChangeScript =
      RedisScript.of(new ClassPathResource("redis/begin_change.lua"), Long.class);
  private final RedisScript<Long> beginRecoveryScript =
      RedisScript.of(new ClassPathResource("redis/begin_recovery.lua"), Long.class);
  private final RedisScript<Long> completeRecoveryScript =
      RedisScript.of(new ClassPathResource("redis/complete_recovery.lua"), Long.class);

  @Override
  public boolean beginChange(UUID dropId, UUID attemptId) {
    return Long.valueOf(1L)
        .equals(
            redisTemplate.execute(
                beginChangeScript,
                List.of(recoveryKey(dropId), inflightKey(dropId)),
                attemptId.toString()));
  }

  @Override
  public void completeChange(UUID dropId, UUID attemptId) {
    redisTemplate.opsForSet().remove(inflightKey(dropId), attemptId.toString());
  }

  @Override
  public boolean beginRecovery(UUID dropId, UUID owner, Duration lease) {
    if (lease == null || lease.toMillis() <= 0) {
      throw new IllegalArgumentException("Recovery lease must be at least one millisecond");
    }
    return Long.valueOf(1L)
        .equals(
            redisTemplate.execute(
                beginRecoveryScript,
                List.of(recoveryKey(dropId), inflightKey(dropId)),
                owner.toString(),
                Long.toString(lease.toMillis())));
  }

  @Override
  public void completeRecovery(UUID dropId, UUID owner) {
    redisTemplate.execute(completeRecoveryScript, List.of(recoveryKey(dropId)), owner.toString());
  }

  static String recoveryKey(UUID dropId) {
    return "drop:" + dropId + ":recovery";
  }

  static String inflightKey(UUID dropId) {
    return "drop:" + dropId + ":inflight";
  }
}
