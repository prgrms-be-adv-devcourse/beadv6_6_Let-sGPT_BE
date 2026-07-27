package com.openat.recommendation.application.service;

import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import com.openat.recommendation.application.port.out.LlmClient;
import com.openat.recommendation.application.service.RecommendationPostProcessor.SelectedSection;
import com.openat.recommendation.application.service.RecommendationResponse.Product;
import com.openat.recommendation.application.service.RecommendationResponse.Section;
import com.openat.recommendation.domain.model.DropMeta;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.domain.service.SeedScorer;
import com.openat.recommendation.infrastructure.cache.RecommendationResultCache;
import com.openat.recommendation.infrastructure.client.ProductDetailClient;
import com.openat.recommendation.infrastructure.client.ProductDetailClient.ProductDetailResponse;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient.SimilarProductResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class RecommendationService {

  private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
  private static final int HOME_MAX_SECTIONS = 3;
  private static final int HOME_MAX_PRODUCTS_PER_SECTION = 4;
  private static final int HOME_MAX_PRODUCTS_TOTAL = 12;
  private static final int DETAIL_MAX_SECTIONS = 1;
  private static final int DETAIL_MAX_PRODUCTS_PER_SECTION = 6;
  private static final int DETAIL_MAX_PRODUCTS_TOTAL = 6;

  private final RecommendationSeedService seedService;
  private final SeedScorer seedScorer;
  private final SearchRecommendClient searchClient;
  private final OpenDropCache openDropCache;
  private final RecommendationPromptBuilder promptBuilder;
  private final LlmClient llmClient;
  private final RecommendationPostProcessor postProcessor;
  private final ProductDetailClient productDetailClient;
  private final RecommendationResultCache resultCache;
  private final PopularProductsCache popularProductsCache;
  private final int fallbackLimit;
  private final Executor executor;

  // 캐시 키별 진행 중인 계산. 같은 키의 동시 미스가 하나의 계산을 공유한다(스탬피드 방지).
  // 완료·예외 어느 쪽으로 끝나도 엔트리를 비워 누수를 막는다.
  private final ConcurrentMap<String, CompletableFuture<RecommendationResponse>> inFlight =
      new ConcurrentHashMap<>();

  // 가상 스레드로 워커 풀이 사라졌으므로, 미스 파이프라인(검색+LLM) 동시 실행을 명시적으로
  // 제한하는 admission valve. 초과분은 다운스트림을 더 때리지 않고 인메모리 폴백으로 흘린다.
  private final Semaphore pipelineLimiter;

  public RecommendationService(
      RecommendationSeedService seedService,
      SeedScorer seedScorer,
      SearchRecommendClient searchClient,
      OpenDropCache openDropCache,
      RecommendationPromptBuilder promptBuilder,
      LlmClient llmClient,
      RecommendationPostProcessor postProcessor,
      ProductDetailClient productDetailClient,
      RecommendationResultCache resultCache,
      PopularProductsCache popularProductsCache,
      @Value("${recommendation.fallback-limit:3}") int fallbackLimit,
      @Value("${recommendation.max-concurrent-pipelines:64}") int maxConcurrentPipelines,
      @Qualifier("recommendationExecutor") Executor executor) {
    this.seedService = seedService;
    this.seedScorer = seedScorer;
    this.searchClient = searchClient;
    this.openDropCache = openDropCache;
    this.promptBuilder = promptBuilder;
    this.llmClient = llmClient;
    this.postProcessor = postProcessor;
    this.productDetailClient = productDetailClient;
    this.resultCache = resultCache;
    this.popularProductsCache = popularProductsCache;
    this.fallbackLimit = fallbackLimit;
    this.pipelineLimiter = new Semaphore(maxConcurrentPipelines);
    this.executor = executor;
  }

  public RecommendationResponse recommend(UUID productId) {
    boolean home = productId == null;
    // 캐시 키 계산·조회는 추천 성공의 전제가 아니다. 키 계산이 실패해도(잘못된 id, 캐시 장애)
    // 폴백까지 가지 못하고 빈 응답이 나가면 안 되므로, 실패 시 캐시만 건너뛴다.
    Optional<String> cacheKey = cacheKey(productId);
    Optional<RecommendationResponse> cached = lookup(home, cacheKey);
    if (cached.isPresent()) {
      return cached.get();
    }
    try {
      return singleFlight(cacheKey, () -> guardedPipeline(home, productId, cacheKey));
    } catch (Exception exception) {
      log.warn("recommendation failed, returning empty response: home={}", home, exception);
      return RecommendationResponse.empty();
    }
  }

  /** 캐시 조회 실패는 미스와 동일하게 취급한다. */
  private Optional<RecommendationResponse> lookup(boolean home, Optional<String> cacheKey) {
    if (cacheKey.isEmpty()) {
      return Optional.empty();
    }
    String key = cacheKey.get();
    try {
      Optional<RecommendationResponse> cached = resultCache.find(key);
      cached.ifPresent(
          ignored -> log.info("recommendation cache hit: home={}, key={}", home, key));
      return cached;
    } catch (RuntimeException exception) {
      log.warn("recommendation cache lookup failed, treating as miss: key={}", key, exception);
      return Optional.empty();
    }
  }

  /**
   * 같은 캐시 키에 동시에 몰린 미스를 한 번의 계산으로 합친다. 먼저 도착한 요청이 계산하고, 그
   * 사이 들어온 요청은 같은 결과를 나눠 받는다. 키가 없는(비로그인 홈) 요청은 합칠 대상이 없어
   * 그냥 계산한다. 완료·예외 어느 쪽이든 {@code finally}에서 엔트리를 비운다.
   */
  private RecommendationResponse singleFlight(
      Optional<String> cacheKey, Supplier<RecommendationResponse> computation) {
    if (cacheKey.isEmpty()) {
      return computation.get();
    }
    String key = cacheKey.get();
    CompletableFuture<RecommendationResponse> mine = new CompletableFuture<>();
    CompletableFuture<RecommendationResponse> running =
        inFlight.computeIfAbsent(key, ignored -> mine);
    if (running != mine) {
      return running.join();
    }
    try {
      RecommendationResponse result = computation.get();
      mine.complete(result);
      return result;
    } catch (RuntimeException exception) {
      mine.completeExceptionally(exception);
      throw exception;
    } finally {
      inFlight.remove(key, mine);
    }
  }

  /** 테스트에서 in-flight 엔트리 누수를 확인하기 위한 창구. */
  int inFlightCount() {
    return inFlight.size();
  }

  /** 미스 파이프라인 동시 실행을 제한한다. 허가를 못 얻으면 폴백만 준다(다운스트림 보호). */
  private RecommendationResponse guardedPipeline(
      boolean home, UUID productId, Optional<String> cacheKey) {
    if (!pipelineLimiter.tryAcquire()) {
      log.warn("recommendation pipeline overloaded, serving fallback: home={}", home);
      return home ? homeFallback("overloaded") : RecommendationResponse.empty();
    }
    try {
      return home ? recommendHome(cacheKey) : recommendDetail(productId, cacheKey);
    } finally {
      pipelineLimiter.release();
    }
  }

  private RecommendationResponse recommendHome(Optional<String> cacheKey) {
    List<Seed> seeds;
    try {
      seeds = seedService.collect();
    } catch (Exception exception) {
      log.warn("recommendation seed collection failed: home=true", exception);
      return homeFallback("seed-collection-failed");
    }
    if (seeds.isEmpty()) {
      return homeFallback("no-seeds");
    }

    List<SimilarProductResponse> candidates;
    try {
      candidates = searchClient.recommend(seeds);
    } catch (HttpClientErrorException exception) {
      log.error("recommendation search failed: home=true, seeds={}", seeds.size(), exception);
      return homeFallback("search-failed");
    } catch (Exception exception) {
      log.warn("recommendation search failed: home=true, seeds={}", seeds.size(), exception);
      return homeFallback("search-failed");
    }

    Set<UUID> purchasedProductIds = purchasedProductIds(seeds);
    candidates =
        candidates.stream()
            .filter(candidate -> !purchasedProductIds.contains(candidate.id()))
            .toList();
    Set<UUID> openIds =
        Set.copyOf(
            openDropCache.filterOpenProductIds(
                candidates.stream().map(SimilarProductResponse::id).toList()));
    candidates = candidates.stream().filter(candidate -> openIds.contains(candidate.id())).toList();
    if (candidates.isEmpty()) {
      return homeFallback("no-open-candidates");
    }

    List<SelectedSection> selected;
    try {
      selected = select(null, candidates);
    } catch (Exception exception) {
      log.warn("recommendation LLM failed: home=true", exception);
      return homeFallback("llm-failed");
    }
    RecommendationResponse response =
        personalizedResponse(selected, true, seeds.size(), candidates.size(), cacheKey);
    return response.sections().isEmpty() ? homeFallback("empty-llm-result") : response;
  }

  private RecommendationResponse recommendDetail(UUID productId, Optional<String> cacheKey) {
    ProductDetailResponse currentProduct = productDetailClient.getProduct(productId);
    List<Seed> seeds = seedScorer.currentProductSeed(productId);

    List<SimilarProductResponse> candidates;
    try {
      candidates = searchClient.recommend(seeds);
    } catch (HttpClientErrorException exception) {
      log.error("recommendation search failed: home=false, seeds={}", seeds.size(), exception);
      return detailFallback(currentProduct.categoryId(), "search-failed");
    } catch (Exception exception) {
      log.warn("recommendation search failed: home=false, seeds={}", seeds.size(), exception);
      return detailFallback(currentProduct.categoryId(), "search-failed");
    }

    candidates =
        candidates.stream().filter(candidate -> !candidate.id().equals(productId)).toList();
    if (candidates.isEmpty()) {
      log.info("recommendation empty: home=false, reason=no-candidates");
      return RecommendationResponse.empty();
    }

    List<SelectedSection> selected;
    try {
      selected = select(currentProduct, candidates);
    } catch (Exception exception) {
      log.warn("recommendation LLM failed: home=false", exception);
      return detailFallback(currentProduct.categoryId(), "llm-failed");
    }
    RecommendationResponse response =
        personalizedResponse(selected, false, seeds.size(), candidates.size(), cacheKey);
    return response.sections().isEmpty()
        ? detailFallback(currentProduct.categoryId(), "empty-llm-result")
        : response;
  }

  private List<SelectedSection> select(
      ProductDetailResponse currentProduct, List<SimilarProductResponse> candidates) {
    String prompt = promptBuilder.build(currentProduct, candidates);
    List<UUID> orderedCandidateIds = candidates.stream().map(SimilarProductResponse::id).toList();
    return postProcessor.process(llmClient.complete(prompt), orderedCandidateIds);
  }

  private RecommendationResponse personalizedResponse(
      List<SelectedSection> selected,
      boolean home,
      int seedCount,
      int candidateCount,
      Optional<String> cacheKey) {
    RecommendationResponse response = assemble(selected, home);
    if (!response.sections().isEmpty()) {
      cacheKey.ifPresent(key -> resultCache.save(key, response));
    }
    log.info(
        "recommendation served: home={}, seeds={}, candidates={}, sections={}",
        home,
        seedCount,
        candidateCount,
        response.sections().size());
    return response;
  }

  /** 키를 못 만들면 캐시만 건너뛰고 파이프라인은 그대로 태운다(잘못된 id → 정상 폴백). */
  private Optional<String> cacheKey(UUID productId) {
    try {
      if (productId != null) {
        return Optional.of(resultCache.cacheKey(productId, null));
      }
      return currentMemberId().map(memberId -> resultCache.cacheKey(null, memberId));
    } catch (RuntimeException exception) {
      log.warn(
          "recommendation cache key unavailable, serving uncached: home={}",
          productId == null,
          exception);
      return Optional.empty();
    }
  }

  private Optional<UUID> currentMemberId() {
    UserContext context = UserContextHolder.get();
    if (context == null) {
      return Optional.empty();
    }
    // X-User-Id가 UUID가 아니면 익명으로 강등한다(요청을 깨뜨리는 대신 폴백 경로로).
    try {
      return Optional.of(UUID.fromString(context.userId()));
    } catch (IllegalArgumentException exception) {
      log.warn("malformed X-User-Id, treating as anonymous");
      return Optional.empty();
    }
  }

  private RecommendationResponse homeFallback(String reason) {
    List<DropMeta> drops = openDropCache.findGeneral(fallbackLimit);
    if (!drops.isEmpty()) {
      return fallbackResponse(true, "이런 드롭은 어떠세요?", drops, reason);
    }
    // 최후 폴백: 열린 드롭이 하나도 없어도 최신 상품으로 홈을 절대 비우지 않는다.
    List<Product> popular = popularProductsCache.get();
    if (popular.isEmpty()) {
      log.info("recommendation fallback empty: home=true, reason={}", reason);
      return RecommendationResponse.empty();
    }
    log.info(
        "recommendation last-resort served: home=true, reason={}, count={}", reason, popular.size());
    return new RecommendationResponse(List.of(new Section("지금 인기 있는 상품", popular)));
  }

  private RecommendationResponse detailFallback(UUID categoryId, String reason) {
    if (categoryId == null) {
      log.info("recommendation fallback empty: home=false, reason={}, category=missing", reason);
      return RecommendationResponse.empty();
    }
    return fallbackResponse(
        false, "이 카테고리의 다른 드롭", openDropCache.findByCategory(categoryId, fallbackLimit), reason);
  }

  private RecommendationResponse fallbackResponse(
      boolean home, String title, List<DropMeta> drops, String reason) {
    if (drops.isEmpty()) {
      log.info("recommendation fallback empty: home={}, reason={}", home, reason);
      return RecommendationResponse.empty();
    }
    List<Product> products = drops.stream().map(this::toProduct).toList();
    log.info(
        "recommendation fallback served: home={}, reason={}, count={}",
        home,
        reason,
        products.size());
    return new RecommendationResponse(List.of(new Section(title, products)));
  }

  private RecommendationResponse assemble(List<SelectedSection> selected, boolean home) {
    int maxSections = home ? HOME_MAX_SECTIONS : DETAIL_MAX_SECTIONS;
    int maxProductsPerSection =
        home ? HOME_MAX_PRODUCTS_PER_SECTION : DETAIL_MAX_PRODUCTS_PER_SECTION;
    int maxProductsTotal = home ? HOME_MAX_PRODUCTS_TOTAL : DETAIL_MAX_PRODUCTS_TOTAL;
    int productCount = 0;
    List<Section> sections = new ArrayList<>();
    for (SelectedSection selectedSection : selected) {
      if (sections.size() >= maxSections || productCount >= maxProductsTotal) {
        break;
      }
      List<Product> products = new ArrayList<>();
      int remainingSlots = Math.min(maxProductsPerSection, maxProductsTotal - productCount);
      List<UUID> sectionProductIds =
          selectedSection.productIds().stream().limit(remainingSlots).toList();
      List<CompletableFuture<Optional<Product>>> productFutures =
          home
              ? List.of()
              : sectionProductIds.stream()
                  .map(
                      productId ->
                          CompletableFuture.supplyAsync(() -> product(false, productId), executor))
                  .toList();
      for (int index = 0; index < sectionProductIds.size(); index++) {
        Optional<Product> product =
            home ? product(true, sectionProductIds.get(index)) : productFutures.get(index).join();
        product.ifPresent(products::add);
      }
      if (!products.isEmpty()) {
        sections.add(new Section(selectedSection.title(), List.copyOf(products)));
        productCount += products.size();
      }
    }
    return new RecommendationResponse(List.copyOf(sections));
  }

  private Set<UUID> purchasedProductIds(List<Seed> seeds) {
    return seeds.stream().filter(Seed::buy).map(Seed::productId).collect(Collectors.toSet());
  }

  private Optional<Product> product(boolean home, UUID productId) {
    try {
      if (home) {
        return openDropCache.findByProductId(productId).map(this::toProduct);
      }
      ProductDetailResponse detail = productDetailClient.getProduct(productId);
      if (detail.price() == null) {
        return Optional.empty();
      }
      return Optional.of(
          new Product(
              detail.id(),
              detail.name(),
              detail.sellerName(),
              detail.price(),
              detail.thumbnailKey()));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private Product toProduct(DropMeta meta) {
    return new Product(
        meta.productId(),
        meta.productName(),
        meta.sellerName(),
        meta.dropPrice(),
        meta.thumbnailKey());
  }
}
