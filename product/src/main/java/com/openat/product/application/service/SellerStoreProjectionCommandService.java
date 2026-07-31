package com.openat.product.application.service;

import com.openat.product.application.usecase.SellerStoreProjectionCommandUseCase;
import com.openat.product.domain.event.SellerStoreProjectionChangedEvent;
import com.openat.product.domain.model.SellerStoreProjection;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import com.openat.support.lock.SearchProjectionReferenceLock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SellerStoreProjectionCommandService implements SellerStoreProjectionCommandUseCase {

  private final SellerStoreProjectionRepository sellerStoreProjectionRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final SearchProjectionReferenceLock searchProjectionReferenceLock;

  @Override
  public void upsert(UUID sellerInfoId, String storeName) {
    searchProjectionReferenceLock.lockSellerForReferenceWrite(sellerInfoId);
    Optional<SellerStoreProjection> existingProjection =
        sellerStoreProjectionRepository.findByIdForUpdate(sellerInfoId);
    if (existingProjection.isPresent()) {
      boolean applied = existingProjection.get().changeStoreName(storeName);
      if (applied) {
        eventPublisher.publishEvent(new SellerStoreProjectionChangedEvent(sellerInfoId));
      }
      return;
    }
    SellerStoreProjection newProjection =
        SellerStoreProjection.project().sellerInfoId(sellerInfoId).storeName(storeName).build();
    sellerStoreProjectionRepository.save(newProjection);
    eventPublisher.publishEvent(new SellerStoreProjectionChangedEvent(sellerInfoId));
  }
}
