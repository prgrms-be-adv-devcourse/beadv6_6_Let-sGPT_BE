package com.openat.recommendation.application.service;

import com.openat.recommendation.application.port.out.OpenDropClient;
import com.openat.recommendation.domain.model.DropMeta;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OpenDropCache {

  private static final Logger log = LoggerFactory.getLogger(OpenDropCache.class);
  private static final Comparator<DropMeta> CLOSE_AT_ORDER =
      Comparator.comparing(DropMeta::closeAt, Comparator.nullsLast(Comparator.naturalOrder()));
  private static final Comparator<DropMeta> REPRESENTATIVE_DROP_ORDER =
      CLOSE_AT_ORDER.thenComparing(DropMeta::dropPrice).thenComparing(DropMeta::dropId);

  private final OpenDropClient openDropClient;
  private final AtomicReference<Map<UUID, DropMeta>> cache = new AtomicReference<>(Map.of());

  public OpenDropCache(OpenDropClient openDropClient) {
    this.openDropClient = openDropClient;
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${recommendation.drop-cache.refresh-interval}")
  public void refresh() {
    try {
      Map<UUID, DropMeta> refreshed =
          openDropClient.getAllOpenDrops().stream()
              .collect(
                  Collectors.toMap(
                      DropMeta::productId,
                      Function.identity(),
                      (existing, replacement) ->
                          REPRESENTATIVE_DROP_ORDER.compare(existing, replacement) <= 0
                              ? existing
                              : replacement,
                      LinkedHashMap::new));
      cache.set(Collections.unmodifiableMap(refreshed));
    } catch (RuntimeException exception) {
      log.warn("Failed to refresh open drop cache; keeping the previous cache", exception);
    }
  }

  public List<UUID> filterOpenProductIds(Collection<UUID> candidateProductIds) {
    Map<UUID, DropMeta> snapshot = cache.get();
    return candidateProductIds.stream()
        .filter(
            productId -> {
              DropMeta drop = snapshot.get(productId);
              return drop != null && isStillOpen(drop);
            })
        .toList();
  }

  public Optional<DropMeta> findByProductId(UUID productId) {
    return Optional.ofNullable(cache.get().get(productId)).filter(this::isStillOpen);
  }

  public List<DropMeta> findByCategory(UUID categoryId, int limit) {
    return cache.get().values().stream()
        .filter(this::isStillOpen)
        .filter(drop -> categoryId.equals(drop.categoryId()))
        .sorted(CLOSE_AT_ORDER)
        .limit(limit)
        .toList();
  }

  public List<DropMeta> findGeneral(int limit) {
    return cache.get().values().stream()
        .filter(this::isStillOpen)
        .sorted(CLOSE_AT_ORDER)
        .limit(limit)
        .toList();
  }

  private boolean isStillOpen(DropMeta drop) {
    return drop.closeAt() == null || drop.closeAt().isAfter(Instant.now());
  }
}
