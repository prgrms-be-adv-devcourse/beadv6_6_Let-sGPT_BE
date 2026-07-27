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

  // 같은 키의 배경 재계산을 짧은 창 안에 반복 예약하지 않기 위한 백오프. 재계산이 계속 실패해
  // 캐시가 열화 상태로 남아 있으면, 매 히트가 재계산을 새로 예약하는 증폭이 생긴다. 이를 막는다.
  private static final long REGEN_BACKOFF_MS = 60_000L;
  private static final int REGEN_BACKOFF_MAX_KEYS = 10_000;
  private final ConcurrentMap<String, Long> lastRegenScheduledAt = new ConcurrentHashMap<>();

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
    this.popularProductsCache = popularProductsCache;
    this.fallbackLimit = fallbackLimit;
    this.pipelineLimiter = new Semaphore(maxConcurrentPipelines);
    this.executor = executor;
  }

  public RecommendationResponse recommend(UUID productId) {
    RecommendationMode mode = RecommendationMode.fromProductId(productId);
    // 캐시 키 계산·조회는 추천 성공의 전제가 아니다. 키 계산이 실패해도(잘못된 id, 캐시 장애)
    // 폴백까지 가지 못하고 빈 응답이 나가면 안 되므로, 실패 시 캐시만 건너뛴다.
    Optional<String> cacheKey = cacheKey(mode, productId);
    try {
      Optional<RecommendationResponse> cached = lookup(mode.isHome(), cacheKey);
      if (cached.isPresent()) {
        // 캐시 히트 서빙도 이 가드 안에서 처리한다. 오염된 캐시(예: productId가 null인 상품)나
        // 서빙 중 발생한 예외가 컨트롤러로 새어 500이 되지 않고, 미스와 동일하게 폴백/빈 응답으로
        // 흡수된다. 아무것도 걸러지지 않는 정상 히트는 여전히 캐시 객체를 그대로 빠르게 돌려준다.
        return serveCached(mode, productId, cacheKey, cached.get());
      }
      return singleFlight(cacheKey, () -> guardedPipeline(mode, productId, cacheKey));
    } catch (Exception exception) {
      log.warn(
          "recommendation failed, returning empty response: home={}", mode.isHome(), exception);
      return RecommendationResponse.empty();
    }
  }

  /**
   * 프리배치 전용: 열린 드롭 상품의 DETAIL 추천을 미리 계산해 {@code rec:detail:{productId}}를
   * 데운다. 요청 경로가 아니라 스케줄러에서만 호출한다. 이미 캐시에 있으면(신선) LLM을 태우지
   * 않고 건너뛴다. 라이브 요청과 동일한 {@link #singleFlight}·{@link #guardedPipeline} 경로를
   * 재사용해 (1) 같은 상품을 두 번 계산하지 않고 (2) {@code pipelineLimiter} 슬롯을 존중해
   * 라이브 트래픽의 LLM 슬롯을 빼앗지 않는다. 캐시 저장은 파이프라인이 개인화 결과를 낼 때만
   * 일어나므로 폴백/과부하 응답은 캐시를 오염시키지 않는다.
   *
   * @return 실제로 개인화 결과가 계산돼 캐시에 저장됐으면 true, 이미 신선해 건너뛰었거나
   *     파이프라인이 폴백/과부하로 흘러 아무것도 저장되지 않았으면 false. 프리배치의
   *     warmed/failed 집계가 정확해지도록 "파이프라인이 돌았는지"가 아니라 "실제로 데워졌는지"에
   *     맞춘다.
   */
  boolean warmDetail(UUID productId) {
    RecommendationMode mode = RecommendationMode.DETAIL;
    Optional<String> cacheKey = cacheKey(mode, productId);
    if (lookup(false, cacheKey).isPresent()) {
      return false;
    }
    singleFlight(cacheKey, () -> guardedPipeline(mode, productId, cacheKey));
    // 캐시는 개인화 결과에서만 기록된다. 파이프라인 후 캐시에 결과가 있으면 실제로 데워진 것이고,
    // 폴백/한도초과로 흘렀으면(저장 없음) 여전히 비어 있어 false가 된다.
    return lookup(false, cacheKey).isPresent();
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
   * 캐시 히트를 내주기 직전, 그 사이 마감된 드롭을 인메모리 {@link OpenDropCache}로 걸러 낸다.
   * LLM/블로킹 호출 없이 열린 상품 집합을 한 번 조회할 뿐이라 ~0 비용이다. 아무것도 걸러지지
   * 않는 비열화 경로는 캐시 객체를 그대로 돌려줘 오늘과 동일하게 빠르다. 필터로 섹션이 통째로
   * 비면(그룹 소멸=열화) 걸러진/폴백 결과를 즉시 내주고, 다음 방문을 위한 전체 재계산을
   * 백그라운드로 예약한다. 요청 스레드는 절대 재계산에 묶이지 않는다.
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
      List<Product> openProducts =
          section.products().stream()
              // 오염된 캐시(productId==null)는 열린 상품 집합 조회·contains에서 NPE를 유발하므로
              // 걸러 낸다. 그 상품은 제외(=제거)될 뿐, 요청 전체를 500으로 죽이지 않는다.
              .filter(product -> product.productId() != null)
              .filter(product -> openIds.contains(product.productId()))
              .toList();
      if (openProducts.size() != section.products().size()) {
        anyRemoved = true;
      }
      if (openProducts.isEmpty()) {
        sectionEmptied = true;
      } else {
        kept.add(new Section(section.title(), openProducts));
      }
    }
    if (!anyRemoved) {
      // 비열화: 오늘과 동일하게 캐시 객체를 그대로 돌려준다.
      return cached;
    }
    // 그룹이 통째로 사라진 열화에서만 재계산을 예약한다(부분 제거는 보수적으로 재계산 안 함).
    if (sectionEmptied) {
      cacheKey.ifPresent(key -> scheduleRegeneration(mode, productId, key));
    }
    if (kept.isEmpty()) {
      // 모든 섹션이 비면 기존 폴백 사다리(일반 열린 드롭 → 최후 인기 상품)로 흘린다. LLM 없음.
      return degradedFallback(mode, productId);
    }
    return new RecommendationResponse(List.copyOf(kept));
  }

  private Set<UUID> openProductIds(List<Section> sections) {
    List<UUID> productIds =
        sections.stream()
            .flatMap(section -> section.products().stream())
            .map(Product::productId)
            // null id는 미리 제거한다. Set.copyOf·contains 모두 null에 적대적이라, 오염된 캐시
            // 하나가 히트 전체를 NPE로 무너뜨리지 않게 한다.
            .filter(Objects::nonNull)
            .toList();
    return Set.copyOf(openDropCache.filterOpenProductIds(productIds));
  }

  /**
   * 캐시 엔트리가 지금도 열화 상태인지 본다. 어떤 섹션이 열린 상품을 하나도 갖지 못하면(그룹
   * 소멸) 열화로 간주한다. {@link #serveCached}의 재계산 예약 기준과 같은 신호를 재사용하므로,
   * 배경 재계산 진입 시 다른 재계산이 이미 신선하게 갱신했는지 정확히 판별할 수 있다.
   */
  private boolean isDegraded(RecommendationResponse cached) {
    List<Section> sections = cached.sections();
    if (sections.isEmpty()) {
      return true;
    }
    Set<UUID> openIds = openProductIds(sections);
    for (Section section : sections) {
      boolean anyOpen =
          section.products().stream()
              .anyMatch(
                  product -> product.productId() != null && openIds.contains(product.productId()));
      if (!anyOpen) {
        return true;
      }
    }
    return false;
  }

  /** 캐시 열화로 모든 섹션이 빈 경우, 기존 폴백 메서드를 그대로 재사용한다(LLM 호출 없음). */
  private RecommendationResponse degradedFallback(RecommendationMode mode, UUID productId) {
    if (mode.isHome()) {
      return homeFallback("cache-all-sections-closed");
    }
    try {
      ProductDetailResponse currentProduct = productDetailClient.getProduct(productId);
      return detailFallback(currentProduct.categoryId(), "cache-all-sections-closed");
    } catch (RuntimeException exception) {
      log.warn(
          "recommendation cache-degraded detail fallback failed: productId={}",
          productId,
          exception);
      return RecommendationResponse.empty();
    }
  }

  /**
   * 열화된 캐시를 내준 뒤, 다음 방문이 신선한 결과를 받도록 전체 파이프라인(LLM 포함)을
   * 백그라운드에서 다시 돌려 {@link RecommendationResultCache}를 갱신한다. 같은 키의 재계산이
   * 이미 진행 중이면(미스 single-flight 포함) 새로 시작하지 않는다. 스케줄 자체가 실패해도
   * 로그만 남기고 응답에는 영향을 주지 않는다.
   */
  private void scheduleRegeneration(RecommendationMode mode, UUID productId, String key) {
    if (inFlight.containsKey(key)) {
      return;
    }
    long now = System.currentTimeMillis();
    Long last = lastRegenScheduledAt.get(key);
    if (last != null && now - last < REGEN_BACKOFF_MS) {
      // 같은 키를 짧은 창 안에 다시 예약하지 않는다(재계산이 계속 실패할 때의 증폭 억제).
      return;
    }
    rememberRegenSchedule(key, now);
    // 배경 스레드에는 요청의 ThreadLocal이 전파되지 않으므로 홈 seed/키 계산에 필요한
    // UserContext를 캡처해 넘긴다(상세는 컨텍스트가 필요 없어 null이어도 무방).
    UserContext capturedContext = UserContextHolder.get();
    try {
      executor.execute(() -> regenerate(mode, productId, key, capturedContext));
    } catch (RuntimeException exception) {
      log.warn("recommendation regeneration could not be scheduled: key={}", key, exception);
    }
  }

  private void rememberRegenSchedule(String key, long now) {
    lastRegenScheduledAt.put(key, now);
    // 무한 증가를 막는 최소 정리: 창을 지난 오래된 엔트리를 이따금 걷어 낸다.
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
      // 재계산 진입 시 캐시를 다시 확인한다. 그 사이 다른 재계산이 신선한 결과로 갱신해 더 이상
      // 열화가 아니면(어떤 섹션도 통째로 마감되지 않았으면) 파이프라인을 태우지 않는다.
      // check-then-act 경합을 없애고, 재계산 실패 시의 무한 재예약 증폭을 함께 줄인다.
      Optional<RecommendationResponse> current = lookup(mode.isHome(), Optional.of(key));
      if (current.isPresent() && !isDegraded(current.get())) {
        return;
      }
      singleFlight(Optional.of(key), () -> guardedPipeline(mode, productId, Optional.of(key)));
    } catch (Throwable throwable) {
      // 배경 실패는 로그만 남긴다. 이미 응답을 받은 사용자에겐 어떤 영향도 없다.
      log.warn("recommendation background regeneration failed: key={}", key, throwable);
    } finally {
      if (capturedContext != null) {
        UserContextHolder.clear();
      }
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
    } catch (RuntimeException | Error throwable) {
      // RuntimeException뿐 아니라 Error까지 잡아 대기자에게 그대로 전파한다. Error를 놓치면
      // mine이 완료되지 않아 running.join()에 묶인 대기자가 영원히 풀리지 않는다.
      mine.completeExceptionally(throwable);
      throw throwable;
    } finally {
      inFlight.remove(key, mine);
      // 어떤 Throwable 경로로 빠져나가도(체크 예외의 sneaky throw 등) 리더가 mine을 완료하지
      // 못한 채 나가면 대기자가 무한 대기한다. 마지막 안전망으로 반드시 풀어 준다.
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

  /** 미스 파이프라인 동시 실행을 제한한다. 허가를 못 얻으면 폴백만 준다(다운스트림 보호). */
  private RecommendationResponse guardedPipeline(
      RecommendationMode mode, UUID productId, Optional<String> cacheKey) {
    if (!pipelineLimiter.tryAcquire()) {
      log.warn("recommendation pipeline overloaded, serving fallback: home={}", mode.isHome());
      return mode.isHome() ? homeFallback("overloaded") : RecommendationResponse.empty();
    }
    try {
      return recommendPipeline(mode, productId, cacheKey);
    } finally {
      pipelineLimiter.release();
    }
  }

  private RecommendationResponse recommendPipeline(
      RecommendationMode mode, UUID productId, Optional<String> cacheKey) {
    ProductDetailResponse currentProduct =
        mode == RecommendationMode.DETAIL ? productDetailClient.getProduct(productId) : null;

    List<Seed> seeds;
    if (mode.isHome()) {
      try {
        seeds = seedService.collect();
      } catch (Exception exception) {
        log.warn("recommendation seed collection failed: home=true", exception);
        return fallback(mode, currentProduct, "seed-collection-failed");
      }
    } else {
      seeds = seedScorer.currentProductSeed(productId);
    }
    if (mode.isHome() && seeds.isEmpty()) {
      return fallback(mode, currentProduct, "no-seeds");
    }

    Optional<List<SimilarProductResponse>> searchResult = searchCandidates(mode, seeds);
    if (searchResult.isEmpty()) {
      return fallback(mode, currentProduct, "search-failed");
    }
    List<SimilarProductResponse> candidates =
        filterCandidates(mode, productId, seeds, searchResult.orElseThrow());

    if (candidates.isEmpty()) {
      if (mode.isHome()) {
        return fallback(mode, currentProduct, "no-open-candidates");
      }
      log.info("recommendation empty: home=false, reason=no-candidates");
      return RecommendationResponse.empty();
    }

    List<SelectedSection> selected;
    try {
      selected = select(mode, currentProduct, candidates);
    } catch (Exception exception) {
      log.warn("recommendation LLM failed: home={}", mode.isHome(), exception);
      return fallback(mode, currentProduct, "llm-failed");
    }
    RecommendationResponse response =
        personalizedResponse(selected, mode, seeds.size(), candidates.size(), cacheKey);
    return response.sections().isEmpty()
        ? fallback(mode, currentProduct, "empty-llm-result")
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
      // 상세: 현재 상품만 제외한다(구매 이력 필터는 홈 전용).
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
    // 홈·상세 공통 규칙: 판매중(열린 드롭)인 후보만 추천한다.
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
    log.info(
        "recommendation served: home={}, seeds={}, candidates={}, sections={}",
        mode.isHome(),
        seedCount,
        candidateCount,
        response.sections().size());
    return response;
  }

  /** 키를 못 만들면 캐시만 건너뛰고 파이프라인은 그대로 태운다(잘못된 id → 정상 폴백). */
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
      RecommendationMode mode, ProductDetailResponse currentProduct, String reason) {
    return switch (mode) {
      case HOME -> homeFallback(reason);
      case DETAIL -> detailFallback(currentProduct.categoryId(), reason);
    };
  }

  private RecommendationResponse homeFallback(String reason) {
    List<DropMeta> drops = openDropCache.findGeneral(fallbackLimit);
    if (!drops.isEmpty()) {
      return fallbackResponse(RecommendationMode.HOME, "이런 드롭은 어떠세요?", drops, reason);
    }
    // 최후 폴백: 열린 드롭이 하나도 없어도 최신 상품으로 홈을 절대 비우지 않는다.
    List<Product> popular = popularProductsCache.get();
    if (popular.isEmpty()) {
      log.info("recommendation fallback empty: home=true, reason={}", reason);
      return RecommendationResponse.empty();
    }
    log.info(
        "recommendation last-resort served: home=true, reason={}, count={}", reason, popular.size());
    return new RecommendationResponse(List.of(new Section("새로 올라온 상품", popular)));
  }

  private RecommendationResponse detailFallback(UUID categoryId, String reason) {
    if (categoryId == null) {
      log.info("recommendation fallback empty: home=false, reason={}, category=missing", reason);
      return RecommendationResponse.empty();
    }
    return fallbackResponse(
        RecommendationMode.DETAIL,
        "이 카테고리의 다른 드롭",
        openDropCache.findByCategory(categoryId, fallbackLimit),
        reason);
  }

  private RecommendationResponse fallbackResponse(
      RecommendationMode mode, String title, List<DropMeta> drops, String reason) {
    if (drops.isEmpty()) {
      log.info("recommendation fallback empty: home={}, reason={}", mode.isHome(), reason);
      return RecommendationResponse.empty();
    }
    List<Product> products = drops.stream().map(this::toProduct).toList();
    log.info(
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
          yield Optional.of(
              new Product(
                  detail.id(),
                  detail.name(),
                  detail.sellerName(),
                  detail.price(),
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
        meta.productName(),
        meta.sellerName(),
        meta.dropPrice(),
        meta.thumbnailKey());
  }
}
