package com.openat.drop.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropInfo;
import com.openat.drop.application.dto.DropSnapshotInfo;
import com.openat.drop.application.usecase.DropQueryUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.DropSearchCondition;
import com.openat.seller.application.usecase.SellerStoreQueryUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DropQueryService implements DropQueryUseCase {

  private final DropRepository dropRepository;
  private final DropCacheRepository dropCacheRepository;
  private final SellerStoreQueryUseCase sellerStoreQueryUseCase;

  @Override
  public DropInfo getDrop(UUID id) {
    Drop drop =
        dropRepository
            .findById(id)
            .orElseThrow(() -> new BusinessException(DropErrorCode.NOT_FOUND));
    Instant now = Instant.now();
    UUID sellerId = drop.getProduct().getSellerId();
    String sellerName = sellerStoreQueryUseCase.findStoreNames(List.of(sellerId)).get(sellerId);
    Long cachedRemaining = dropCacheRepository.findRemaining(List.of(id)).get(id);
    return toInfo(drop, sellerName, now, cachedRemaining);
  }

  @Override
  public DropSnapshotInfo getDropSnapshot(UUID dropId) {
    Drop drop =
        dropRepository
            .findWithProductById(dropId)
            .orElseThrow(() -> new BusinessException(DropErrorCode.NOT_FOUND));
    return DropSnapshotInfo.from(drop);
  }

  @Override
  public Page<DropInfo> searchDrops(DropSearchCondition condition, Pageable pageable) {
    Instant now = Instant.now();
    Page<Drop> drops = dropRepository.search(condition, now, pageable);
    Map<UUID, Long> remainingByDrop =
        dropCacheRepository.findRemaining(drops.getContent().stream().map(Drop::getId).toList());
    Map<UUID, String> storeNames =
        sellerStoreQueryUseCase.findStoreNames(
            drops.getContent().stream().map(drop -> drop.getProduct().getSellerId()).toList());
    return drops.map(
        drop ->
            toInfo(
                drop,
                storeNames.get(drop.getProduct().getSellerId()),
                now,
                remainingByDrop.get(drop.getId())));
  }

  private DropInfo toInfo(Drop drop, String sellerName, Instant now, Long cachedRemaining) {
    long remaining = resolveRemaining(drop, now, cachedRemaining);
    DropStatus status = drop.resolveStatus(now, remaining);
    return DropInfo.of(drop, sellerName, (int) remaining, status);
  }

  private long resolveRemaining(Drop drop, Instant now, Long cachedRemaining) {
    if (cachedRemaining != null) {
      return Math.max(0, cachedRemaining);
    }
    if (drop.isBeforeOpen(now)) {
      return drop.getTotalQuantity();
    }
    return 0;
  }
}
