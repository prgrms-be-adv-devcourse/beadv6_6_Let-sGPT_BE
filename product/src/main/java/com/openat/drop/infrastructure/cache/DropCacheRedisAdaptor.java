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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private final RedisScript<Long> compensateScript =
      RedisScript.of(new ClassPathResource("redis/compensate.lua"), Long.class);
  private final RedisScript<String> closeScript =
      RedisScript.of(new ClassPathResource("redis/close.lua"), String.class);
  private final RedisScript<String> restoreCloseScript =
      RedisScript.of(new ClassPathResource("redis/restore_close.lua"), String.class);
  private final RedisScript<String> warmScript =
      RedisScript.of(new ClassPathResource("redis/warm.lua"), String.class);
  private final RedisScript<Long> evictBeforeOpenScript =
      RedisScript.of(new ClassPathResource("redis/evict_before_open.lua"), Long.class);

  @Override
  public void warm(DropCacheState state) {
    List<String> arguments = new ArrayList<>();
    arguments.add(Long.toString(state.remaining()));
    arguments.add(Long.toString(state.openAt().toEpochMilli()));
    arguments.add(nullableNumber(state.closeAt() == null ? null : state.closeAt().toEpochMilli()));
    arguments.add(nullableNumber(state.limitPerUser()));
    arguments.add(Long.toString(warmingTtl(state.closeAt()).toMillis()));
    state.buyers().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              arguments.add(entry.getKey().toString());
              arguments.add(Long.toString(entry.getValue()));
            });
    redisTemplate.execute(
        warmScript,
        List.of(dropKey(state.dropId()), buyersKey(state.dropId())),
        arguments.toArray());
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

  @Override
  public void markClosed(UUID dropId) {
    redisTemplate.execute(
        closeScript,
        List.of(dropKey(dropId), buyersKey(dropId)),
        Long.toString(properties.closeMargin().toMillis()));
  }

  @Override
  public void restoreCloseAt(UUID dropId, Instant closeAt) {
    redisTemplate.execute(
        restoreCloseScript,
        List.of(dropKey(dropId)),
        nullableNumber(closeAt == null ? null : closeAt.toEpochMilli()));
  }

  @Override
  public boolean evictBeforeOpen(UUID dropId) {
    Long evicted =
        redisTemplate.execute(
            evictBeforeOpenScript, List.of(dropKey(dropId), buyersKey(dropId)));
    return Long.valueOf(1L).equals(evicted);
  }

  @Override
  public StockCommandResult deduct(StockMutation mutation) {
    List<String> keys =
        List.of(
            dropKey(mutation.dropId()), buyersKey(mutation.dropId()), orderKey(mutation.orderId()));
    String raw =
        redisTemplate.execute(
            deductScript,
            keys,
            mutation.buyerId().toString(),
            Integer.toString(mutation.quantity()),
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
  public Optional<Long> compensateDeduct(StockMutation mutation) {
    return compensate(
        mutation.dropId(),
        orderKey(mutation.orderId()),
        mutation.buyerId(),
        mutation.quantity(),
        -mutation.quantity());
  }

  @Override
  public Optional<Long> compensateRollback(StockMutation mutation) {
    return compensate(
        mutation.dropId(),
        rollbackKey(mutation.orderId()),
        mutation.buyerId(),
        -mutation.quantity(),
        mutation.quantity());
  }

  private Optional<Long> compensate(
      UUID dropId, String idemKey, UUID buyerId, int remainingDelta, int buyerDelta) {
    Long remaining =
        redisTemplate.execute(
            compensateScript,
            List.of(dropKey(dropId), buyersKey(dropId), idemKey),
            buyerId.toString(),
            Integer.toString(remainingDelta),
            Integer.toString(buyerDelta));
    return Optional.ofNullable(remaining);
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

  private String nullableNumber(Number value) {
    return value == null ? UNSET_SENTINEL : value.toString();
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
