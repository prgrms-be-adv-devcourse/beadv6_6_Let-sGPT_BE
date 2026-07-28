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
 * 최후 폴백용 최신 상품 스냅샷. 현재 유일한 소비자는 상품 상세 폴백이다 — 홈은 드롭 쇼케이스라
 * 열린 드롭이 없으면 빈 응답을 주고 이 단계를 쓰지 않는다.
 *
 * <p>상세의 드롭 폴백 단계는 카테고리의 열린 드롭에 의존한다. 이 캐시는 드롭 상태와 무관한 최신
 * 상품을 담아, 카테고리에 열린 드롭이 없어도 상세 화면이 비지 않게 한다. OpenDropCache와 같은
 * 주기로 스케줄 갱신하므로 요청 경로에 실시간 호출이 없다(지연 0). 갱신이 실패해도 직전 스냅샷을
 * 유지한다.
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
                          // 드롭 무관 상품이다. dropId=null이 곧 "상품 페이지로 보내라"는 계약.
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
