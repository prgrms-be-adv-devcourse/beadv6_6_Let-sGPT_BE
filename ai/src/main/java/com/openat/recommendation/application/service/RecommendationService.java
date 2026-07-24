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
import java.util.concurrent.Executor;
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
  private final int fallbackLimit;
  private final Executor executor;

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
      @Value("${recommendation.fallback-limit:3}") int fallbackLimit,
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
    this.fallbackLimit = fallbackLimit;
    this.executor = executor;
  }

  public RecommendationResponse recommend(UUID productId) {
    boolean home = productId == null;
    Optional<String> cacheKey = cacheKey(productId);
    Optional<RecommendationResponse> cached = cacheKey.flatMap(resultCache::find);
    if (cached.isPresent()) {
      log.info("recommendation cache hit: home={}, key={}", home, cacheKey.orElseThrow());
      return cached.get();
    }
    try {
      return home ? recommendHome(cacheKey) : recommendDetail(productId, cacheKey);
    } catch (Exception exception) {
      log.warn("recommendation failed, returning empty response: home={}", home, exception);
      return RecommendationResponse.empty();
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

  private Optional<String> cacheKey(UUID productId) {
    if (productId != null) {
      return Optional.of(resultCache.cacheKey(productId, null));
    }
    UserContext context = UserContextHolder.get();
    if (context == null) {
      return Optional.empty();
    }
    return Optional.of(resultCache.cacheKey(null, UUID.fromString(context.userId())));
  }

  private RecommendationResponse homeFallback(String reason) {
    return fallbackResponse(true, "이런 드롭은 어떠세요?", openDropCache.findGeneral(fallbackLimit), reason);
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
