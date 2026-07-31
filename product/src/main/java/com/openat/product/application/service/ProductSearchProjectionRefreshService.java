package com.openat.product.application.service;

import com.openat.category.domain.event.CategoryDeletingEvent;
import com.openat.category.domain.event.CategoryUpdatedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.event.SellerStoreProjectionChangedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductSearchProjectionRefreshService {

  private final ProductRepository productRepository;
  private final ApplicationEventPublisher eventPublisher;

  @EventListener
  public void onSellerStoreProjectionChanged(SellerStoreProjectionChangedEvent event) {
    List<Product> products =
        productRepository.findAllBySellerIdForUpdate(event.sellerInfoId());
    refresh(products);
  }

  @EventListener
  public void onCategoryUpdated(CategoryUpdatedEvent event) {
    List<Product> products =
        productRepository.findAllByCategoryIdForUpdate(event.categoryId());
    refresh(products);
  }

  @EventListener
  public void onCategoryDeleting(CategoryDeletingEvent event) {
    List<Product> products =
        productRepository.findAllByCategoryIdForUpdate(event.categoryId());
    for (Product product : products) {
      product.removeCategory(event.categoryId());
      eventPublisher.publishEvent(new ProductUpdatedEvent(product));
    }
  }

  private void refresh(List<Product> products) {
    for (Product product : products) {
      product.refreshSearchProjection();
      eventPublisher.publishEvent(new ProductUpdatedEvent(product));
    }
  }
}
