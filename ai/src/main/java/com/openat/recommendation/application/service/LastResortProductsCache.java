package com.openat.recommendation.application.service;

import com.openat.recommendation.application.service.RecommendationResponse.Product;
import com.openat.recommendation.infrastructure.client.LatestProductsClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 최후 폴백용 최신 상품 스냅샷. 소비자는 상세 폴백뿐이다 — 홈은 드롭이 없으면 빈 응답을 준다.
 *
 * <p>드롭 상태와 무관해 카테고리 드롭이 없어도 채운다. 스케줄로 미리 채워 요청 경로 지연은 0이다.
 */
@Component
public class LastResortProductsCache {

  private static final Logger log = LoggerFactory.getLogger(LastResortProductsCache.class);

  private final LatestProductsClient latestProductsClient;
  private final int size;
  private final AtomicReference<List<Product>> cache = new AtomicReference<>(List.of());

  public LastResortProductsCache(
      LatestProductsClient latestProductsClient,
      @Value("${recommendation.last-resort.size:8}") int size) {
    this.latestProductsClient = latestProductsClient;
    this.size = size;
  }

  @Scheduled(
      scheduler = "recommendationTaskScheduler",
      initialDelay = 0,
      fixedDelayString = "${recommendation.drop-cache.refresh-interval}")
  public void refresh() {
    try {
      List<Product> products =
          latestProductsClient.latest(size).stream()
              .filter(product -> product.price() != null)
              // 상품 서비스가 size를 지키지 않아도 스냅샷 크기는 우리가 보장한다.
              .limit(size)
              .map(
                  product ->
                      new Product(
                          product.id(),
                          // dropId=null이 곧 "상품 페이지로 보내라"는 계약이다.
                          null,
                          product.name(),
                          product.sellerName(),
                          product.price(),
                          product.thumbnailKey()))
              .toList();
      cache.set(products);
    } catch (RuntimeException exception) {
      log.warn("Failed to refresh latest products cache; keeping the previous snapshot", exception);
    }
  }

  public List<Product> get() {
    return cache.get();
  }
}
