package com.openat.product.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.dto.ProductChangeInfo;
import com.openat.product.application.dto.ProductInfo;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.model.SellerStoreProjection;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.product.domain.repository.ProductTombstone;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService implements ProductQueryUseCase {

  private final ProductRepository productRepository;
  private final SellerStoreProjectionRepository sellerStoreProjectionRepository;

  @Override
  public ProductInfo getById(UUID id) {
    Product product = getProductOrThrow(id);
    Map<UUID, String> sellerNames = findSellerNames(List.of(product.getSellerId()));
    String sellerName = sellerNames.get(product.getSellerId());
    return ProductInfo.from(product, sellerName);
  }

  @Override
  public Product getOwnedProduct(UUID id, UUID sellerId) {
    Product product = getProductOrThrow(id);
    if (!product.getSellerId().equals(sellerId)) {
      throw new BusinessException(ProductErrorCode.NOT_OWNER);
    }
    return product;
  }

  @Override
  public Page<ProductInfo> searchProducts(ProductSearchCondition condition, Pageable pageable) {
    Page<Product> productPage = productRepository.search(condition, pageable);
    List<UUID> sellerIds = productPage.getContent().stream().map(Product::getSellerId).toList();
    Map<UUID, String> sellerNames = findSellerNames(sellerIds);
    return productPage.map(
        product -> ProductInfo.from(product, sellerNames.get(product.getSellerId())));
  }

  @Override
  public Map<UUID, String> findSellerNames(Collection<UUID> sellerIds) {
    if (sellerIds.isEmpty()) {
      return Map.of();
    }
    List<SellerStoreProjection> projections =
        sellerStoreProjectionRepository.findAllById(sellerIds);
    Map<UUID, String> sellerNames = new HashMap<>();
    for (SellerStoreProjection projection : projections) {
      sellerNames.put(projection.getSellerInfoId(), projection.getStoreName());
    }
    return sellerNames;
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<ProductChangeInfo> searchChanges(Instant changedAfter) {
    List<Product> changedProducts = productRepository.searchChangedAliveSince(changedAfter);
    List<ProductTombstone> tombstones = productRepository.searchTombstonesSince(changedAfter);

    List<UUID> sellerIds = changedProducts.stream().map(Product::getSellerId).toList();
    Map<UUID, String> sellerNames = findSellerNames(sellerIds);

    List<ProductChangeInfo> changes = new ArrayList<>();
    for (Product product : changedProducts) {
      String sellerName = sellerNames.get(product.getSellerId());
      changes.add(ProductChangeInfo.upsert(product, sellerName, changedAfter));
    }
    for (ProductTombstone tombstone : tombstones) {
      changes.add(ProductChangeInfo.deletion(tombstone));
    }

    changes.sort(
        Comparator.comparing(ProductChangeInfo::changedAt).thenComparing(ProductChangeInfo::id));
    return changes;
  }

  private Product getProductOrThrow(UUID id) {
    return productRepository
        .findById(id)
        .orElseThrow(() -> new BusinessException(ProductErrorCode.NOT_FOUND));
  }
}
