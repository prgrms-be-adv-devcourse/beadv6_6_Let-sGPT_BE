package com.openat.product.application.service;

import com.openat.category.domain.event.CategoryDeletingEvent;
import com.openat.category.domain.event.CategoryUpdatedEvent;
import com.openat.product.domain.event.SellerStoreProjectionChangedEvent;
import com.openat.product.domain.repository.ProductSearchProjectionRefreshTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductSearchProjectionRefreshService {

  private final ProductSearchProjectionRefreshTargetRepository refreshTargetRepository;

  @EventListener
  public void onSellerStoreProjectionChanged(SellerStoreProjectionChangedEvent event) {
    refreshTargetRepository.enqueueBySellerId(event.sellerInfoId());
  }

  @EventListener
  public void onCategoryUpdated(CategoryUpdatedEvent event) {
    refreshTargetRepository.enqueueByCategoryId(event.categoryId());
  }

  @EventListener
  public void onCategoryDeleting(CategoryDeletingEvent event) {
    refreshTargetRepository.enqueueByCategoryId(event.categoryId());
  }
}
