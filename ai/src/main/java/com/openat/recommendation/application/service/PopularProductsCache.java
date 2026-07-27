package com.openat.recommendation.application.service;

import com.openat.recommendation.application.service.RecommendationResponse.Product;
import com.openat.recommendation.infrastructure.client.LatestProductsClient;
import com.openat.recommendation.infrastructure.client.LatestProductsClient.LatestProduct;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 최후 폴백용 최신 상품 스냅샷.
 *
 * <p>모든 홈 폴백 단계는 열린 드롭에 의존한다 — 열린 드롭이 없으면 홈이 구조적으로 빈다. 이
 * 캐시는 드롭 상태와 무관한 최신 상품을 담아 홈이 절대 비지 않도록 한다. OpenDropCache와 같은
 * 주기로 스케줄 갱신하므로 요청 경로에 실시간 호출이 없다(지연 0). 갱신이 실패해도 직전
 * 스냅샷을 유지한다.
 */
@Component
public class PopularProductsCache {

  private static final Logger log = LoggerFactory.getLogger(PopularProductsCache.class);

  private final LatestProductsClient latestProductsClient;
  private final int size;
  private final AtomicReference<List<Product>> cache = new AtomicReference<>(List.of());

  public PopularProductsCache(
      LatestProductsClient latestProductsClient,
      @Value("${recommendation.last-resort.size:8}") int size) {
    this.latestProductsClient = latestProductsClient;
    this.size = size;
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${recommendation.drop-cache.refresh-interval}")
  public void refresh() {
    try {
      List<Product> products =
          latestProductsClient.latest(size).stream()
              .filter(product -> product.price() != null)
              .map(
                  product ->
                      new Product(
                          product.id(),
                          product.name(),
                          product.sellerName(),
                          product.price(),
                          product.thumbnailKey()))
              .toList();
      cache.set(products);
    } catch (RuntimeException exception) {
      log.warn("Failed to refresh popular products cache; keeping the previous snapshot", exception);
    }
  }

  public List<Product> get() {
    return cache.get();
  }
}
