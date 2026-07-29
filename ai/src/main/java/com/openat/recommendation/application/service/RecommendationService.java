package com.openat.recommendation.application.service;

import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import com.openat.recommendation.application.port.out.LlmClient;
import com.openat.recommendation.application.service.RecommendationMetrics.PipelineSample;
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
import java.util.Objects;
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
  // 드롭이 아닌 상품을 내주는 단계의 제목 — 상세 전용, 홈에는 이 단계가 없다.
  private static final String LAST_RESORT_TITLE = "이런 상품은 어떠세요?";
  private final RecommendationSeedService seedService;
  private final SeedScorer seedScorer;
  private final SearchRecommendClient searchClient;
  private final OpenDropCache openDropCache;
  private final RecommendationPromptBuilder promptBuilder;
  private final LlmClient llmClient;
  private final RecommendationPostProcessor postProcessor;
  private final ProductDetailClient productDetailClient;
  private final RecommendationResultCache resultCache;
  private final LastResortProductsCache lastResortProductsCache;
  private final RecommendationMetrics metrics;
  private final int fallbackLimit;
  private final Executor executor;

  // 캐시 키별 진행 중인 계산 — 같은 키의 동시 미스를 하나로 합쳐 스탬피드를 막는다.
  private final ConcurrentMap<String, CompletableFuture<RecommendationResponse>> inFlight =
      new ConcurrentHashMap<>();

  // 배경 재계산 반복 예약 억제 — 재계산이 계속 실패하면 매 히트가 새로 예약해 증폭된다.
  private static final long REGEN_BACKOFF_MS = 60_000L;
  private static final int REGEN_BACKOFF_MAX_KEYS = 10_000;
  private final ConcurrentMap<String, Long> lastRegenScheduledAt = new ConcurrentHashMap<>();

  // 가상 스레드로 워커 풀이 사라져 미스 파이프라인 동시 실행을 직접 제한한다 — 초과분은 폴백.
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
      LastResortProductsCache lastResortProductsCache,
      RecommendationMetrics metrics,
      @Value("${recommendation.fallback-limit:4}") int fallbackLimit,
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
    this.lastResortProductsCache = lastResortProductsCache;
    this.metrics = metrics;
    this.fallbackLimit = fallbackLimit;
    this.pipelineLimiter = new Semaphore(maxConcurrentPipelines);
    this.executor = executor;
  }

  public RecommendationResponse recommend(UUID productId) {
    RecommendationMode mode = RecommendationMode.fromProductId(productId);
    // 결과 경로가 여럿이라 현재 상품 제외는 경로마다 걸지 않고 조립된 응답에서 한 번에 처리한다.
    return withoutCurrentProduct(productId, compute(mode, productId));
  }

  private RecommendationResponse compute(RecommendationMode mode, UUID productId) {
    Optional<String> cacheKey = cacheKey(mode, productId);
    try {
      Optional<RecommendationResponse> cached = lookup(mode, cacheKey);
      // 적중률 왜곡을 막으려 요청 경로에서만 센다 — 프리배치·배경 재계산의 조회는 빼야 한다.
      metrics.cacheLookup(mode, cached.isPresent());
      if (cached.isPresent()) {
        // 히트 서빙도 가드 안이다 — 오염된 캐시나 서빙 예외가 500이 아니라 폴백으로 흡수된다.
        return serveCached(mode, productId, cacheKey, cached.get());
      }
      return singleFlight(cacheKey, () -> guardedPipeline(mode, productId, cacheKey, true));
    } catch (Exception exception) {
      log.warn(
          "recommendation failed, returning empty response: home={}", mode.isHome(), exception);
      return RecommendationResponse.empty();
    }
  }

  /** 지금 보고 있는 상품을 응답에서 뺀다. 줄어든 개수는 채우지 않는다 — 추가 조회는 지연만 늘린다. */
  private RecommendationResponse withoutCurrentProduct(
      UUID productId, RecommendationResponse response) {
    if (productId == null || !contains(response, productId)) {
      return response;
    }
    List<Section> kept = new ArrayList<>(response.sections().size());
    for (Section section : response.sections()) {
      List<Product> products =
          section.products().stream()
              .filter(product -> !productId.equals(product.productId()))
              .toList();
      if (!products.isEmpty()) {
        kept.add(new Section(section.title(), products));
      }
    }
    log.debug("recommendation excluded current product: productId={}", productId);
    return new RecommendationResponse(List.copyOf(kept));
  }

  private boolean contains(RecommendationResponse response, UUID productId) {
    return response.sections().stream()
        .flatMap(section -> section.products().stream())
        .anyMatch(product -> productId.equals(product.productId()));
  }

  /**
   * 프리배치 전용. 라이브 요청과 같은 single-flight·pipelineLimiter 경로를 재사용해 중복 계산과 라이브 LLM 슬롯 잠식을 막는다.
   *
   * @return 파이프라인이 돌았는지가 아니라 실제로 데워졌는지 — 폴백·과부하는 저장이 없어 false다.
   */
  boolean warmDetail(UUID productId) {
    RecommendationMode mode = RecommendationMode.DETAIL;
    Optional<String> cacheKey = cacheKey(mode, productId);
    if (lookup(mode, cacheKey).isPresent()) {
      return false;
    }
    singleFlight(cacheKey, () -> guardedPipeline(mode, productId, cacheKey, false));
    // 캐시는 개인화 결과에서만 기록되므로, 파이프라인 후 캐시 유무가 곧 데워졌는지다.
    return lookup(mode, cacheKey).isPresent();
  }

  private Optional<RecommendationResponse> lookup(
      RecommendationMode mode, Optional<String> cacheKey) {
    if (cacheKey.isEmpty()) {
      return Optional.empty();
    }
    String key = cacheKey.get();
    try {
      Optional<RecommendationResponse> cached = resultCache.find(key);
      cached.ifPresent(
          ignored -> log.debug("recommendation cache hit: home={}, key={}", mode.isHome(), key));
      return cached;
    } catch (RuntimeException exception) {
      log.warn("recommendation cache lookup failed, treating as miss: key={}", key, exception);
      return Optional.empty();
    }
  }

  /**
   * 캐시 히트를 내주기 직전, 그 사이 마감된 드롭을 인메모리 {@link OpenDropCache}로 걸러 낸다.
   *
   * <p>섹션이 통째로 비는 열화에서는 걸러진 결과를 즉시 내주고 재계산은 배경으로 넘긴다 — 요청 스레드는 묶이지 않는다.
   */
  private RecommendationResponse serveCached(
      RecommendationMode mode,
      UUID productId,
      Optional<String> cacheKey,
      RecommendationResponse cached) {
    List<Section> sections = cached.sections();
    if (sections.isEmpty()) {
      return cached;
    }
    Set<UUID> openIds = openProductIds(sections);
    List<Section> kept = new ArrayList<>(sections.size());
    boolean anyRemoved = false;
    boolean sectionEmptied = false;
    for (Section section : sections) {
      List<Product> retained =
          section.products().stream()
              // 오염된 캐시의 null id는 contains에서 NPE를 내므로, 제거만 하고 요청은 살린다.
              .filter(product -> product.productId() != null)
              .filter(product -> openIds.contains(product.productId()))
              // 현재 상품이 담긴 옛 캐시도 열화로 봐야 재계산이 예약돼 TTL 12h를 안 기다린다.
              .filter(product -> !Objects.equals(product.productId(), productId))
              // dropId가 없던 배포 전 캐시는 링크·정가만 담고 있어 현재 메타로 카드를 복원한다.
              .map(
                  product ->
                      product.dropId() == null
                          ? openDropCache
                              .findByProductId(product.productId())
                              .map(this::toProduct)
                              // 재조회 사이에 드롭이 닫혀도 링크를 지우지 않고 재계산에 맡긴다.
                              .orElse(product)
                          : product)
              .toList();
      // 크기가 같아도 dropId·드롭가 복원은 새 응답으로 내야 아래 fast-path가 옛 카드를 안 준다.
      if (!retained.equals(section.products())) {
        anyRemoved = true;
      }
      if (retained.isEmpty()) {
        sectionEmptied = true;
      } else {
        kept.add(new Section(section.title(), retained));
      }
    }
    if (!anyRemoved) {
      return cached;
    }
    // 그룹 소멸에서만 재계산을 예약한다 — 부분 제거는 보수적으로 재계산하지 않는다.
    if (sectionEmptied) {
      cacheKey.ifPresent(key -> scheduleRegeneration(mode, productId, key));
    }
    if (kept.isEmpty()) {
      return degradedFallback(mode, productId);
    }
    return new RecommendationResponse(List.copyOf(kept));
  }

  private Set<UUID> openProductIds(List<Section> sections) {
    List<UUID> productIds =
        sections.stream()
            .flatMap(section -> section.products().stream())
            .map(Product::productId)
            // Set.copyOf·contains 모두 null에 적대적이라 오염된 id 하나가 히트 전체를 깨뜨린다.
            .filter(Objects::nonNull)
            .toList();
    return Set.copyOf(openDropCache.filterOpenProductIds(productIds));
  }

  /** 열화 판정. {@link #serveCached}의 재계산 예약 기준과 같은 신호를 재사용한다. */
  private boolean isDegraded(UUID productId, RecommendationResponse cached) {
    List<Section> sections = cached.sections();
    if (sections.isEmpty()) {
      return true;
    }
    Set<UUID> openIds = openProductIds(sections);
    for (Section section : sections) {
      boolean anyServable =
          section.products().stream()
              .anyMatch(
                  product ->
                      product.productId() != null
                          && openIds.contains(product.productId())
                          && !Objects.equals(product.productId(), productId));
      if (!anyServable) {
        return true;
      }
    }
    return false;
  }

  /** 캐시 열화로 모든 섹션이 비면 기존 폴백 사다리를 그대로 재사용한다 — LLM 호출은 없다. */
  private RecommendationResponse degradedFallback(RecommendationMode mode, UUID productId) {
    if (mode.isHome()) {
      return homeFallback("cache-all-sections-closed");
    }
    try {
      ProductDetailResponse currentProduct = productDetailClient.getProduct(productId);
      return detailFallback(productId, currentProduct.categoryId(), "cache-all-sections-closed");
    } catch (RuntimeException exception) {
      log.warn(
          "recommendation cache-degraded detail fallback failed: productId={}",
          productId,
          exception);
      return RecommendationResponse.empty();
    }
  }

  /** 열화된 캐시를 내준 뒤, 다음 방문을 위해 배경에서 전체 파이프라인을 다시 돌려 캐시를 갱신한다. */
  private void scheduleRegeneration(RecommendationMode mode, UUID productId, String key) {
    if (inFlight.containsKey(key)) {
      return;
    }
    long now = System.currentTimeMillis();
    Long last = lastRegenScheduledAt.get(key);
    if (last != null && now - last < REGEN_BACKOFF_MS) {
      return;
    }
    rememberRegenSchedule(key, now);
    // 배경 스레드엔 요청 ThreadLocal이 전파되지 않아 홈 시드·키 계산용 UserContext를 캡처한다.
    UserContext capturedContext = UserContextHolder.get();
    try {
      executor.execute(() -> regenerate(mode, productId, key, capturedContext));
    } catch (RuntimeException exception) {
      log.warn("recommendation regeneration could not be scheduled: key={}", key, exception);
    }
  }

  private void rememberRegenSchedule(String key, long now) {
    lastRegenScheduledAt.put(key, now);
    if (lastRegenScheduledAt.size() > REGEN_BACKOFF_MAX_KEYS) {
      lastRegenScheduledAt.values().removeIf(timestamp -> now - timestamp >= REGEN_BACKOFF_MS);
    }
  }

  private void regenerate(
      RecommendationMode mode, UUID productId, String key, UserContext capturedContext) {
    if (capturedContext != null) {
      UserContextHolder.set(capturedContext);
    }
    try {
      // 진입 시 재확인 — 그 사이 다른 재계산이 갱신했으면 파이프라인을 태우지 않는다.
      Optional<RecommendationResponse> current = lookup(mode, Optional.of(key));
      if (current.isPresent() && !isDegraded(productId, current.get())) {
        return;
      }
      singleFlight(
          Optional.of(key), () -> guardedPipeline(mode, productId, Optional.of(key), false));
    } catch (Throwable throwable) {
      log.warn("recommendation background regeneration failed: key={}", key, throwable);
    } finally {
      if (capturedContext != null) {
        UserContextHolder.clear();
      }
    }
  }

  /** 같은 키의 동시 미스를 한 번의 계산으로 합친다. 키가 없는 비로그인 홈은 합칠 대상이 없어 그냥 계산한다. */
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
    } catch (RuntimeException | Error throwable) {
      // Error까지 잡아 전파한다 — 놓치면 running.join()에 묶인 대기자가 영원히 풀리지 않는다.
      mine.completeExceptionally(throwable);
      throw throwable;
    } finally {
      inFlight.remove(key, mine);
      // sneaky throw 등으로 리더가 mine을 완료 못 한 채 나가도 대기자를 반드시 풀어 준다.
      if (!mine.isDone()) {
        mine.completeExceptionally(
            new IllegalStateException("single-flight leader terminated without completing"));
      }
    }
  }

  /** 테스트에서 in-flight 엔트리 누수를 확인하기 위한 창구. */
  int inFlightCount() {
    return inFlight.size();
  }

  /**
   * 미스 파이프라인 동시 실행을 제한한다. 허가를 못 얻으면 폴백만 준다 — 다운스트림 보호.
   *
   * <p>타이머는 허가를 얻은 뒤 시작한다. 셰딩된 ≈0ms 표본이 섞이면 과부하가 심할수록 지연이 낮아 보인다.
   */
  private RecommendationResponse guardedPipeline(
      RecommendationMode mode, UUID productId, Optional<String> cacheKey, boolean measured) {
    if (!pipelineLimiter.tryAcquire()) {
      metrics.overloaded(measured);
      log.warn("recommendation pipeline overloaded, serving fallback: home={}", mode.isHome());
      // 배경 셰딩은 사용자가 받은 폴백이 아니라 reason을 나눈다 — overloaded는 요청 경로만 남는다.
      String reason = measured ? "overloaded" : "overloaded-background";
      // 과부하 중에는 카테고리 조회(HTTP)를 하지 않는다 — categoryId=null로 인메모리만 태운다.
      return mode.isHome() ? homeFallback(reason) : detailFallback(productId, null, reason);
    }
    PipelineSample sample = metrics.startPipeline(mode, measured);
    try {
      return recommendPipeline(mode, productId, cacheKey, sample);
    } finally {
      pipelineLimiter.release();
      sample.stop();
    }
  }

  private RecommendationResponse recommendPipeline(
      RecommendationMode mode, UUID productId, Optional<String> cacheKey, PipelineSample sample) {
    ProductDetailResponse currentProduct =
        mode == RecommendationMode.DETAIL ? productDetailClient.getProduct(productId) : null;

    List<Seed> seeds;
    if (mode.isHome()) {
      try {
        seeds = seedService.collect();
      } catch (Exception exception) {
        log.warn("recommendation seed collection failed: home=true", exception);
        return fallback(mode, productId, currentProduct, "seed-collection-failed");
      }
    } else {
      seeds = seedScorer.currentProductSeed(productId);
    }
    if (mode.isHome() && seeds.isEmpty()) {
      // 시드가 없으면 파이프라인을 아예 타지 않아 ≈0ms다 — 타이머 표본에서 빼고 건수만 센다.
      sample.discard();
      return fallback(mode, productId, currentProduct, "no-seeds");
    }

    Optional<List<SimilarProductResponse>> searchResult = searchCandidates(mode, seeds);
    if (searchResult.isEmpty()) {
      return fallback(mode, productId, currentProduct, "search-failed");
    }
    List<SimilarProductResponse> candidates =
        filterCandidates(mode, productId, seeds, searchResult.orElseThrow());

    if (candidates.isEmpty()) {
      if (mode.isHome()) {
        return fallback(mode, productId, currentProduct, "no-open-candidates");
      }
      return detailFallback(productId, null, "no-candidates");
    }

    List<SelectedSection> selected;
    try {
      selected = select(mode, currentProduct, candidates);
    } catch (Exception exception) {
      log.warn("recommendation LLM failed: home={}", mode.isHome(), exception);
      return fallback(mode, productId, currentProduct, "llm-failed");
    }
    RecommendationResponse response =
        personalizedResponse(selected, mode, seeds.size(), candidates.size(), cacheKey);
    return response.sections().isEmpty()
        ? fallback(mode, productId, currentProduct, "empty-llm-result")
        : response;
  }

  private Optional<List<SimilarProductResponse>> searchCandidates(
      RecommendationMode mode, List<Seed> seeds) {
    List<SimilarProductResponse> candidates;
    try {
      candidates = searchClient.recommend(seeds);
    } catch (HttpClientErrorException exception) {
      log.error(
          "recommendation search failed: home={}, seeds={}",
          mode.isHome(),
          seeds.size(),
          exception);
      return Optional.empty();
    } catch (Exception exception) {
      log.warn(
          "recommendation search failed: home={}, seeds={}",
          mode.isHome(),
          seeds.size(),
          exception);
      return Optional.empty();
    }
    return Optional.of(candidates);
  }

  private List<SimilarProductResponse> filterCandidates(
      RecommendationMode mode,
      UUID productId,
      List<Seed> seeds,
      List<SimilarProductResponse> candidates) {
    List<SimilarProductResponse> preFiltered;
    if (mode == RecommendationMode.DETAIL) {
      preFiltered =
          candidates.stream()
              .filter(candidate -> Objects.nonNull(candidate.id()))
              .filter(candidate -> !candidate.id().equals(productId))
              .toList();
    } else {
      Set<UUID> purchasedProductIds = purchasedProductIds(seeds);
      preFiltered =
          candidates.stream()
              .filter(candidate -> Objects.nonNull(candidate.id()))
              .filter(candidate -> !purchasedProductIds.contains(candidate.id()))
              .toList();
    }
    Set<UUID> openIds =
        Set.copyOf(
            openDropCache.filterOpenProductIds(
                preFiltered.stream().map(SimilarProductResponse::id).toList()));
    return preFiltered.stream().filter(candidate -> openIds.contains(candidate.id())).toList();
  }

  private List<SelectedSection> select(
      RecommendationMode mode,
      ProductDetailResponse currentProduct,
      List<SimilarProductResponse> candidates) {
    String prompt = promptBuilder.build(mode, currentProduct, candidates);
    List<UUID> orderedCandidateIds = candidates.stream().map(SimilarProductResponse::id).toList();
    return postProcessor.process(llmClient.complete(prompt), orderedCandidateIds);
  }

  private RecommendationResponse personalizedResponse(
      List<SelectedSection> selected,
      RecommendationMode mode,
      int seedCount,
      int candidateCount,
      Optional<String> cacheKey) {
    RecommendationResponse response = assemble(selected, mode);
    if (!response.sections().isEmpty()) {
      cacheKey.ifPresent(key -> resultCache.save(key, response));
    }
    log.debug(
        "recommendation served: home={}, seeds={}, candidates={}, sections={}",
        mode.isHome(),
        seedCount,
        candidateCount,
        response.sections().size());
    return response;
  }

  /** 키를 못 만들면 캐시만 건너뛰고 파이프라인은 그대로 태운다 — 잘못된 id도 정상 폴백을 받는다. */
  private Optional<String> cacheKey(RecommendationMode mode, UUID productId) {
    try {
      return mode.cacheKey(resultCache, productId);
    } catch (RuntimeException exception) {
      log.warn(
          "recommendation cache key unavailable, serving uncached: home={}",
          mode.isHome(),
          exception);
      return Optional.empty();
    }
  }

  private RecommendationResponse fallback(
      RecommendationMode mode,
      UUID productId,
      ProductDetailResponse currentProduct,
      String reason) {
    return switch (mode) {
      case HOME -> homeFallback(reason);
      case DETAIL -> detailFallback(productId, currentProduct.categoryId(), reason);
    };
  }

  /** 홈은 드롭 쇼케이스라 열린 드롭이 없으면 상품으로 채우지 않고 빈 응답을 준다 — 정책이다. */
  private RecommendationResponse homeFallback(String reason) {
    metrics.fallback(RecommendationMode.HOME, reason);
    List<DropMeta> drops = openDropCache.findGeneral(fallbackLimit);
    if (drops.isEmpty()) {
      metrics.empty(RecommendationMode.HOME, reason);
      log.debug("recommendation fallback empty: home=true, reason={}", reason);
      return RecommendationResponse.empty();
    }
    return dropSection(RecommendationMode.HOME, "이런 드롭은 어떠세요?", drops, reason);
  }

  /**
   * 상세는 드롭 여부와 무관하게 항상 무언가를 보여 준다 — 카테고리 드롭이 없으면 최신 상품으로 내려간다.
   *
   * <p>단, 드롭이 아닌 것을 "드롭"이라 부르지 않는다. 섹션 제목이 두 단계를 구분한다.
   */
  private RecommendationResponse detailFallback(
      UUID currentProductId, UUID categoryId, String reason) {
    metrics.fallback(RecommendationMode.DETAIL, reason);
    // 같은 카테고리 드롭에는 현재 상품 자신이 들어 있다. 판정 전에 빼야 자기 자신만 남은 경우도
    // 최후 폴백으로 내려간다. +1은 현재 상품이 상위 N에 들 때의 언더필을 메운다.
    List<DropMeta> drops =
        categoryId == null
            ? List.of()
            : openDropCache.findByCategory(categoryId, fallbackLimit + 1).stream()
                .filter(drop -> !Objects.equals(drop.productId(), currentProductId))
                .limit(fallbackLimit)
                .toList();
    if (!drops.isEmpty()) {
      return dropSection(RecommendationMode.DETAIL, "이 카테고리의 다른 드롭", drops, reason);
    }
    // 최후 폴백 캐시는 스케줄로 채워지므로 기동 직후엔 비어 있어 빈 응답이 될 수밖에 없다.
    List<Product> latest = lastResortProductsCache.get();
    if (latest.isEmpty()) {
      metrics.empty(RecommendationMode.DETAIL, reason);
      log.debug("recommendation fallback empty: home=false, reason={}", reason);
      return RecommendationResponse.empty();
    }
    metrics.lastResort(RecommendationMode.DETAIL, reason);
    log.debug(
        "recommendation last-resort served: home=false, reason={}, count={}",
        reason,
        latest.size());
    return new RecommendationResponse(List.of(new Section(LAST_RESORT_TITLE, latest)));
  }

  private RecommendationResponse dropSection(
      RecommendationMode mode, String title, List<DropMeta> drops, String reason) {
    List<Product> products = drops.stream().map(this::toProduct).toList();
    log.debug(
        "recommendation fallback served: home={}, reason={}, count={}",
        mode.isHome(),
        reason,
        products.size());
    return new RecommendationResponse(List.of(new Section(title, products)));
  }

  private RecommendationResponse assemble(List<SelectedSection> selected, RecommendationMode mode) {
    int productCount = 0;
    List<Section> sections = new ArrayList<>();
    for (SelectedSection selectedSection : selected) {
      if (sections.size() >= mode.maxSections() || productCount >= mode.maxProductsTotal()) {
        break;
      }
      List<Product> products = new ArrayList<>();
      int remainingSlots =
          Math.min(mode.maxProductsPerSection(), mode.maxProductsTotal() - productCount);
      List<UUID> sectionProductIds =
          selectedSection.productIds().stream().limit(remainingSlots).toList();
      List<CompletableFuture<Optional<Product>>> productFutures =
          mode.isHome()
              ? List.of()
              : sectionProductIds.stream()
                  .map(
                      productId ->
                          CompletableFuture.supplyAsync(() -> product(mode, productId), executor))
                  .toList();
      for (int index = 0; index < sectionProductIds.size(); index++) {
        Optional<Product> product =
            mode.isHome()
                ? product(mode, sectionProductIds.get(index))
                : productFutures.get(index).join();
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

  private Optional<Product> product(RecommendationMode mode, UUID productId) {
    try {
      return switch (mode) {
        case HOME -> openDropCache.findByProductId(productId).map(this::toProduct);
        case DETAIL -> {
          ProductDetailResponse detail = productDetailClient.getProduct(productId);
          if (detail.price() == null) {
            yield Optional.empty();
          }
          Optional<DropMeta> openDrop = openDropCache.findByProductId(productId);
          yield Optional.of(
              new Product(
                  detail.id(),
                  // 상품 상세엔 드롭 정보가 없어 인메모리로 채운다. 못 찾으면 null — 상품 페이지 계약.
                  openDrop.map(DropMeta::dropId).orElse(null),
                  detail.name(),
                  detail.sellerName(),
                  openDrop.map(DropMeta::dropPrice).orElse(detail.price()),
                  detail.thumbnailKey()));
        }
      };
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  private Product toProduct(DropMeta meta) {
    return new Product(
        meta.productId(),
        meta.dropId(),
        meta.productName(),
        meta.sellerName(),
        meta.dropPrice(),
        meta.thumbnailKey());
  }
}
