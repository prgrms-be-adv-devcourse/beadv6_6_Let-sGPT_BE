package com.openat.drop.infrastructure.cache;

import com.openat.config.DropProperties;
import com.openat.drop.domain.model.StockCommandStatus;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropCacheState;
import com.openat.drop.domain.repository.StockCommandResult;
import com.openat.drop.domain.repository.StockMutation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DropCacheRedisAdaptor implements DropCacheRepository {

  private static final String UNSET_SENTINEL = "-1";
  private static final String REMAINING_FIELD = "remaining";

  private final StringRedisTemplate redisTemplate;
  private final DropProperties properties;

  private final RedisScript<String> deductScript =
      RedisScript.of(new ClassPathResource("redis/deduct.lua"), String.class);
  private final RedisScript<String> rollbackScript =
      RedisScript.of(new ClassPathResource("redis/rollback.lua"), String.class);
  private final RedisScript<String> compensateScript =
      RedisScript.of(new ClassPathResource("redis/compensate.lua"), String.class);
  private final RedisScript<String> closeScript =
      RedisScript.of(new ClassPathResource("redis/close.lua"), String.class);

  @Override
  public void warm(DropCacheState state) {
    String dropKey = dropKey(state.dropId());
    Duration ttl = warmingTtl(state.closeAt());

    redisTemplate.opsForHash().putAll(dropKey, dropFields(state));
    redisTemplate.expire(dropKey, ttl);

    if (!state.buyers().isEmpty()) {
      String buyersKey = buyersKey(state.dropId());
      redisTemplate.opsForHash().putAll(buyersKey, buyerFields(state));
      redisTemplate.expire(buyersKey, ttl);
    }
  }

  @Override
  public Map<UUID, Long> findRemaining(Collection<UUID> dropIds) {
    if (dropIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = List.copyOf(dropIds);
    byte[] field = REMAINING_FIELD.getBytes(StandardCharsets.UTF_8);
    List<Object> results =
        redisTemplate.executePipelined(
            (RedisCallback<Object>)
                connection -> {
                  for (UUID id : ids) {
                    connection
                        .hashCommands()
                        .hGet(dropKey(id).getBytes(StandardCharsets.UTF_8), field);
                  }
                  return null;
                });

    Map<UUID, Long> remainingByDrop = new HashMap<>();
    for (int index = 0; index < ids.size(); index++) {
      Object value = results.get(index);
      if (value != null) {
        remainingByDrop.put(ids.get(index), Long.parseLong(value.toString()));
      }
    }
    return remainingByDrop;
  }

  private Map<String, String> dropFields(DropCacheState state) {
    String closeAt = UNSET_SENTINEL;
    if (state.closeAt() != null) {
      closeAt = Long.toString(state.closeAt().toEpochMilli());
    }

    String limitPerUser = UNSET_SENTINEL;
    if (state.limitPerUser() != null) {
      limitPerUser = Integer.toString(state.limitPerUser());
    }

    Map<String, String> fields = new HashMap<>();
    fields.put(REMAINING_FIELD, Long.toString(state.remaining()));
    fields.put("openAt", Long.toString(state.openAt().toEpochMilli()));
    fields.put("closeAt", closeAt);
    fields.put("limitPerUser", limitPerUser);
    return fields;
  }

  private Map<String, String> buyerFields(DropCacheState state) {
    Map<String, String> fields = new HashMap<>();
    state
        .buyers()
        .forEach((buyerId, quantity) -> fields.put(buyerId.toString(), Long.toString(quantity)));
    return fields;
  }

  @Override
  public void markClosed(UUID dropId, Instant now) {
    redisTemplate.execute(closeScript, List.of(dropKey(dropId)), Long.toString(now.toEpochMilli()));
  }

  @Override
  public void evict(UUID dropId) {
    redisTemplate.delete(List.of(dropKey(dropId), buyersKey(dropId)));
  }

  @Override
  public StockCommandResult deduct(StockMutation mutation, Instant now) {
    List<String> keys =
        List.of(
            dropKey(mutation.dropId()), buyersKey(mutation.dropId()), orderKey(mutation.orderId()));
    String raw =
        redisTemplate.execute(
            deductScript,
            keys,
            mutation.buyerId().toString(),
            Integer.toString(mutation.quantity()),
            Long.toString(now.toEpochMilli()),
            Long.toString(properties.idempotencyTtl().toSeconds()));
    return parse(raw);
  }

  @Override
  public StockCommandResult rollback(StockMutation mutation) {
    List<String> keys =
        List.of(
            dropKey(mutation.dropId()),
            buyersKey(mutation.dropId()),
            rollbackKey(mutation.orderId()));
    String raw =
        redisTemplate.execute(
            rollbackScript,
            keys,
            mutation.buyerId().toString(),
            Integer.toString(mutation.quantity()),
            Long.toString(properties.idempotencyTtl().toSeconds()));
    return parse(raw);
  }

  @Override
  public void compensateDeduct(StockMutation mutation) {
    compensate(
        mutation.dropId(),
        orderKey(mutation.orderId()),
        mutation.buyerId(),
        mutation.quantity(),
        -mutation.quantity());
  }

  @Override
  public void compensateRollback(StockMutation mutation) {
    compensate(
        mutation.dropId(),
        rollbackKey(mutation.orderId()),
        mutation.buyerId(),
        -mutation.quantity(),
        mutation.quantity());
  }

  private void compensate(
      UUID dropId, String idemKey, UUID buyerId, int remainingDelta, int buyerDelta) {
    redisTemplate.execute(
        compensateScript,
        List.of(dropKey(dropId), buyersKey(dropId), idemKey),
        buyerId.toString(),
        Integer.toString(remainingDelta),
        Integer.toString(buyerDelta));
  }

  private StockCommandResult parse(String raw) {
    int separator = raw.indexOf(':');
    StockCommandStatus status = StockCommandStatus.valueOf(raw.substring(0, separator));
    long remaining = Long.parseLong(raw.substring(separator + 1));
    return new StockCommandResult(status, remaining);
  }

  private Duration warmingTtl(Instant closeAt) {
    if (closeAt == null) {
      return properties.nullCloseTtl();
    }
    Duration ttl = Duration.between(Instant.now(), closeAt).plus(properties.closeMargin());
    if (ttl.isNegative()) {
      return properties.closeMargin();
    }
    return ttl;
  }

  private String dropKey(UUID dropId) {
    return "drop:" + dropId;
  }

  private String buyersKey(UUID dropId) {
    return dropKey(dropId) + ":buyers";
  }

  private String orderKey(UUID orderId) {
    return "order:" + orderId;
  }

  private String rollbackKey(UUID orderId) {
    return orderKey(orderId) + ":rollback";
  }
}
