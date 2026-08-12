package com.openat.drop.application.service;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DropCacheRecoveryService {

  private final DropRepository dropRepository;
  private final DropCacheRepository dropCacheRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void restoreCloseAt(UUID dropId) {
    Drop drop = dropRepository.findByIdForUpdate(dropId).orElse(null);
    if (drop == null || drop.getStatus() == DropStatus.CLOSE) {
      return;
    }
    dropCacheRepository.restoreCloseAt(dropId, drop.getCloseAt());
  }
}
