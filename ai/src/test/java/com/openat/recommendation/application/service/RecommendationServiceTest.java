package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import com.openat.recommendation.application.port.out.LlmClient;
import com.openat.recommendation.application.service.RecommendationPostProcessor.SelectedSection;
import com.openat.recommendation.domain.model.DropMeta;
import com.openat.recommendation.domain.model.DropStatus;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.domain.service.SeedScorer;
import com.openat.recommendation.infrastructure.cache.RecommendationResultCache;
import com.openat.recommendation.infrastructure.client.ProductDetailClient;
import com.openat.recommendation.infrastructure.client.ProductDetailClient.ProductDetailResponse;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient.SimilarProductResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

  @Mock RecommendationSeedService seedService;
  @Spy SeedScorer seedScorer = new SeedScorer(0.3, 0.5, 0.1, 0.85, 10, 10);
  @Mock SearchRecommendClient searchClient;
  @Mock OpenDropCache openDropCache;
  @Mock RecommendationPromptBuilder promptBuilder;
  @Mock LlmClient llmClient;
  @Mock RecommendationPostProcessor postProcessor;
  @Mock ProductDetailClient productDetailClient;
  @Mock RecommendationResultCache resultCache;
  @Mock LastResortProductsCache lastResortProductsCache;

  private RecommendationService service;
  private ExecutorService executor;
  private SimpleMeterRegistry meterRegistry;
  private RecommendationMetrics metrics;

  @BeforeEach
  void setUp() {
    executor = Executors.newFixedThreadPool(4);
    meterRegistry = new SimpleMeterRegistry();
    metrics = new RecommendationMetrics(meterRegistry);
    // cacheKey는 순수 계산 메서드 — 실제 키 생성 로직을 그대로 사용
    lenient()
        .when(resultCache.cacheKey(any(), any()))
        .thenAnswer(
            inv -> {
              UUID pid = inv.getArgument(0);
              UUID mid = inv.getArgument(1);
              return pid == null ? "rec:" + mid + ":home" : "rec:detail:" + pid;
            });
    service =
        new RecommendationService(
            seedService,
            seedScorer,
            searchClient,
            openDropCache,
            promptBuilder,
            llmClient,
            postProcessor,
            productDetailClient,
            resultCache,
            lastResortProductsCache,
            metrics,
            3,
            64,
            executor);
  }

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
    executor.shutdownNow();
  }

  @Test
  void recommend_forDetail_usesSameSharedCacheKeyForMemberAndAnonymous() {
    UUID memberId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    RecommendationResponse cached = new RecommendationResponse(List.of());
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    when(resultCache.find("rec:detail:" + productId)).thenReturn(Optional.of(cached));

    assertThat(service.recommend(productId)).isSameAs(cached);
    UserContextHolder.clear();
    assertThat(service.recommend(productId)).isSameAs(cached);

    verify(resultCache, times(2)).find("rec:detail:" + productId);
    verify(seedService, never()).collect();
    verify(searchClient, never()).recommend(any());
    verify(llmClient, never()).complete(any());
  }

  @Test
  void recommend_whenPersonalizedSuccess_savesResult() {
    UUID memberId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    stubHomeUntilPrompt(id);
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(id)))
        .thenReturn(List.of(new SelectedSection("추천", List.of(id))));
    when(openDropCache.findByProductId(id)).thenReturn(Optional.of(drop(id, UUID.randomUUID())));

    RecommendationResponse response = service.recommend(null);

    verify(resultCache).save("rec:" + memberId + ":home", response);
  }

  @Test
  void recommend_whenPersonalizedSelectionAssemblesToEmpty_doesNotSaveResult() {
    UUID memberId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    stubHomeUntilPrompt(id);
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(id)))
        .thenReturn(List.of(new SelectedSection("추천", List.of(id))));
    when(openDropCache.findByProductId(id)).thenReturn(Optional.empty());

    RecommendationResponse response = service.recommend(null);

    assertThat(response.sections()).isEmpty();
    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void recommend_whenFallbackReturned_doesNotSaveResult() {
    UUID memberId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    service.recommend(null);

    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void recommend_resolvesLlmIndexUsingTheSameCandidateOrderAsThePrompt() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UUID thirdId = UUID.randomUUID();
    List<SimilarProductResponse> candidates =
        List.of(candidate(firstId), candidate(secondId), candidate(thirdId));
    RecommendationService serviceWithRealSelection =
        new RecommendationService(
            seedService,
            seedScorer,
            searchClient,
            openDropCache,
            new RecommendationPromptBuilder(),
            llmClient,
            new RecommendationPostProcessor(new ObjectMapper()),
            productDetailClient,
            resultCache,
            lastResortProductsCache,
            metrics,
            3,
            64,
            executor);
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(candidates);
    when(openDropCache.filterOpenProductIds(List.of(firstId, secondId, thirdId)))
        .thenReturn(List.of(firstId, secondId, thirdId));
    when(llmClient.complete(anyString()))
        .thenReturn("{\"sections\":[{\"title\":\"추천\",\"items\":[2]}]}");
    when(openDropCache.findByProductId(secondId))
        .thenReturn(Optional.of(drop(secondId, UUID.randomUUID())));

    RecommendationResponse response = serviceWithRealSelection.recommend(null);

    assertThat(response.sections()).hasSize(1);
    assertThat(response.sections().get(0).products())
        .singleElement()
        .extracting(RecommendationResponse.Product::productId)
        .isEqualTo(secondId);
  }

  @Test
  void recommend_forAnonymousHome_skipsResultCache() {
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    service.recommend(null);

    verify(resultCache, never()).find(any());
    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void recommend_whenSeedsAreEmpty_doesNotCallSearch() {
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    assertThat(service.recommend(null).sections()).isEmpty();
    verify(searchClient, never()).recommend(any());
  }

  @Test
  void recommend_forHome_assemblesDropCacheMetadata() {
    UUID id = UUID.randomUUID();
    stubHomeUntilPrompt(id);
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(id)))
        .thenReturn(List.of(new SelectedSection("추천", List.of(id))));
    when(openDropCache.findByProductId(id)).thenReturn(Optional.of(drop(id, UUID.randomUUID())));

    var response = service.recommend(null);

    assertThat(response.sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo("추천");
              assertThat(section.products())
                  .singleElement()
                  .satisfies(
                      product -> {
                        assertThat(product.productId()).isEqualTo(id);
                        assertThat(product.name()).isEqualTo("드롭 상품");
                        assertThat(product.sellerName()).isEqualTo("판매자");
                        assertThat(product.price()).isEqualTo(900L);
                        assertThat(product.thumbnailUrl()).isEqualTo("thumb");
                      });
            });
  }

  @Test
  void recommend_forDetail_keepsCandidatesAndAssemblesProductMetadata() {
    UUID currentId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    ProductDetailResponse selected = product(candidateId, "선택", 200L);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(candidateId))));
    when(productDetailClient.getProduct(candidateId)).thenReturn(selected);

    var response = service.recommend(currentId);

    assertThat(response.sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.products())
                    .singleElement()
                    .satisfies(
                        product -> {
                          assertThat(product.productId()).isEqualTo(candidateId);
                          assertThat(product.name()).isEqualTo("선택");
                          assertThat(product.sellerName()).isEqualTo("판매자");
                          assertThat(product.price()).isEqualTo(200L);
                        }));
    verify(openDropCache).filterOpenProductIds(List.of(candidateId));
    verify(seedService, never()).collect();
    verify(seedScorer).currentProductSeed(currentId);
  }

  @Test
  void recommend_forDetail_fillsDropIdFromOpenDropCacheWithoutExtraHttpCall() {
    UUID currentId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    DropMeta openDrop = drop(candidateId, UUID.randomUUID());
    ProductDetailResponse current = product(currentId, "현재", 100L);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(candidateId))));
    when(productDetailClient.getProduct(candidateId))
        .thenReturn(product(candidateId, "선택", 200L));
    when(openDropCache.findByProductId(candidateId)).thenReturn(Optional.of(openDrop));

    var response = service.recommend(currentId);

    assertThat(response.sections().get(0).products())
        .singleElement()
        .extracting(RecommendationResponse.Product::dropId)
        .isEqualTo(openDrop.dropId());
  }

  @Test
  void recommend_forDetailWhenDropClosedBetweenFilterAndAssemble_leavesDropIdNull() {
    UUID currentId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(candidateId))));
    when(productDetailClient.getProduct(candidateId))
        .thenReturn(product(candidateId, "선택", 200L));
    when(openDropCache.findByProductId(candidateId)).thenReturn(Optional.empty());

    var response = service.recommend(currentId);

    assertThat(response.sections().get(0).products())
        .singleElement()
        .extracting(RecommendationResponse.Product::dropId)
        .isNull();
  }

  @Test
  void recommend_forDetail_fetchesProductsConcurrentlyAndPreservesSelectionOrder()
      throws Exception {
    UUID currentId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any()))
        .thenReturn(List.of(candidate(firstId), candidate(secondId)));
    when(openDropCache.filterOpenProductIds(List.of(firstId, secondId)))
        .thenReturn(List.of(firstId, secondId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(firstId), candidate(secondId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(firstId, secondId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(firstId, secondId))));
    when(productDetailClient.getProduct(firstId))
        .thenAnswer(
            ignored -> {
              started.countDown();
              release.await(1, TimeUnit.SECONDS);
              return product(firstId, "첫째", 200L);
            });
    when(productDetailClient.getProduct(secondId))
        .thenAnswer(
            ignored -> {
              started.countDown();
              release.await(1, TimeUnit.SECONDS);
              return product(secondId, "둘째", 300L);
            });

    CompletableFuture<RecommendationResponse> response =
        CompletableFuture.supplyAsync(() -> service.recommend(currentId));

    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    RecommendationResponse result = response.get(1, TimeUnit.SECONDS);
    assertThat(result.sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.products())
                    .extracting(RecommendationResponse.Product::productId)
                    .containsExactly(firstId, secondId));
  }

  @Test
  void recommend_forDetail_excludesCurrentProductFromCandidatesAndPrompt() {
    UUID currentId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    when(searchClient.recommend(any()))
        .thenReturn(List.of(candidate(currentId), candidate(otherId)));
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(openDropCache.filterOpenProductIds(List.of(otherId))).thenReturn(List.of(otherId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(otherId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(otherId))).thenReturn(List.of());

    service.recommend(currentId);

    verify(promptBuilder).build(RecommendationMode.DETAIL, current, List.of(candidate(otherId)));
    verify(postProcessor).process("raw", List.of(otherId));
  }

  @Test
  void recommend_forDetail_doesNotApplyPurchasedProductFilter() {
    UUID currentId = UUID.randomUUID();
    UUID purchasedId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    ProductDetailResponse selected = product(purchasedId, "선택", 200L);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(purchasedId)));
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(openDropCache.filterOpenProductIds(List.of(purchasedId)))
        .thenReturn(List.of(purchasedId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(purchasedId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(purchasedId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(purchasedId))));
    when(productDetailClient.getProduct(purchasedId)).thenReturn(selected);

    var response = service.recommend(currentId);

    verify(promptBuilder).build(RecommendationMode.DETAIL, current, List.of(candidate(purchasedId)));
    verify(postProcessor).process("raw", List.of(purchasedId));
    verify(seedService, never()).collect();
    assertThat(response.sections())
        .flatExtracting(RecommendationResponse.Section::products)
        .extracting(RecommendationResponse.Product::productId)
        .containsExactly(purchasedId);
  }

  @Test
  void recommend_forDetail_excludesCandidatesThatAreNotOpenDrops() {
    UUID currentId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    UUID closedId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    ProductDetailResponse selected = product(openId, "열림", 200L);
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any()))
        .thenReturn(List.of(candidate(openId), candidate(closedId)));
    // 마감된 드롭(closedId)은 열린 상품 집합에서 빠진다 → 상세 후보에서도 제외되어야 한다.
    when(openDropCache.filterOpenProductIds(List.of(openId, closedId)))
        .thenReturn(List.of(openId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(openId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(openId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(openId))));
    when(productDetailClient.getProduct(openId)).thenReturn(selected);

    var response = service.recommend(currentId);

    // 마감 후보는 LLM 프롬프트에도, 최종 추천에도 들어가지 않는다.
    verify(promptBuilder).build(RecommendationMode.DETAIL, current, List.of(candidate(openId)));
    assertThat(response.sections())
        .flatExtracting(RecommendationResponse.Section::products)
        .extracting(RecommendationResponse.Product::productId)
        .containsExactly(openId);
  }

  @Test
  void recommend_forHome_capsSectionsAtThreeAndProductsAtFourPerSectionAndTwelveTotal() {
    List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(16).toList();
    List<SimilarProductResponse> candidates = ids.stream().map(this::candidate).toList();
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(candidates);
    when(openDropCache.filterOpenProductIds(ids)).thenReturn(ids);
    when(promptBuilder.build(RecommendationMode.HOME, null, candidates)).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", ids))
        .thenReturn(
            List.of(
                new SelectedSection("첫째", ids.subList(0, 5)),
                new SelectedSection("둘째", ids.subList(5, 10)),
                new SelectedSection("셋째", ids.subList(10, 15)),
                new SelectedSection("넷째", ids.subList(15, 16))));
    List.of(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13)
        .forEach(
            index -> {
              UUID id = ids.get(index);
              when(openDropCache.findByProductId(id))
                  .thenReturn(Optional.of(drop(id, UUID.randomUUID())));
            });

    var response = service.recommend(null);

    assertThat(response.sections()).hasSize(3);
    assertThat(response.sections())
        .allSatisfy(section -> assertThat(section.products()).hasSize(4));
    assertThat(response.sections())
        .flatExtracting(RecommendationResponse.Section::products)
        .hasSize(12);
  }

  @Test
  void recommend_forDetail_capsAtOneSectionAndSixProducts() {
    UUID currentId = UUID.randomUUID();
    List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(8).toList();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    List<SimilarProductResponse> candidates = ids.stream().map(this::candidate).toList();
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any())).thenReturn(candidates);
    when(openDropCache.filterOpenProductIds(ids)).thenReturn(ids);
    when(promptBuilder.build(RecommendationMode.DETAIL, current, candidates)).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", ids))
        .thenReturn(
            List.of(
                new SelectedSection("첫째", ids.subList(0, 7)),
                new SelectedSection("둘째", ids.subList(7, 8))));
    ids.subList(0, 6)
        .forEach(
            id -> when(productDetailClient.getProduct(id)).thenReturn(product(id, "선택", 200L)));

    var response = service.recommend(currentId);

    assertThat(response.sections())
        .singleElement()
        .satisfies(section -> assertThat(section.products()).hasSize(6));
  }

  @Test
  void recommend_excludesPurchasedProductsFromHomeCandidatesAndPrompt() {
    UUID purchasedId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    List<Seed> seeds = List.of(new Seed(purchasedId, 0.5, true));
    when(seedService.collect()).thenReturn(seeds);
    when(searchClient.recommend(seeds))
        .thenReturn(List.of(candidate(purchasedId), candidate(candidateId)));
    when(openDropCache.filterOpenProductIds(List.of(candidateId))).thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.HOME, null, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId))).thenReturn(List.of());

    service.recommend(null);

    verify(promptBuilder).build(RecommendationMode.HOME, null, List.of(candidate(candidateId)));
  }

  @Test
  void recommend_forDetailWhenSearchFails_servesCategoryFallback() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 4)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", fallback.productId());
  }

  @Test
  void recommend_forDetailWhenLlmFails_servesCategoryFallback() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), categoryId);
    ProductDetailResponse current = product(currentId, categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenThrow(new RuntimeException("llm"));
    when(openDropCache.findByCategory(categoryId, 4)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", fallback.productId());
  }

  @Test
  void recommend_forDetail_usesOnlyCurrentProductSeedWhenLoggedIn() {
    UUID currentId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    when(productDetailClient.getProduct(currentId))
        .thenReturn(product(currentId, UUID.randomUUID()));
    when(searchClient.recommend(any())).thenReturn(List.of());

    service.recommend(currentId);

    verify(seedService, never()).collect();
    verify(seedScorer).currentProductSeed(currentId);
    verify(searchClient).recommend(List.of(new Seed(currentId, 0.9, false)));
  }

  @Test
  void recommend_forDetailWhenCurrentProductFails_returnsEmpty() {
    UUID currentId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenThrow(new RuntimeException("product"));

    assertThat(service.recommend(currentId).sections()).isEmpty();
    verify(seedService, never()).collect();
  }

  @Test
  void recommend_forDetailWhenCategoryIsNull_skipsCategoryDropsAndServesLastResort() {
    UUID currentId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, null));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(lastResortProductsCache.get()).thenReturn(List.of(latestProduct()));

    assertThat(service.recommend(currentId).sections())
        .singleElement()
        .satisfies(section -> assertThat(section.title()).isEqualTo("이런 상품은 어떠세요?"));
    verify(openDropCache, never()).findByCategory(any(), anyInt());
  }

  @Test
  void recommend_forDetailWhenCategoryHasNoOpenDrops_servesLastResortUnderProductTitle() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 4)).thenReturn(List.of());
    when(lastResortProductsCache.get()).thenReturn(List.of(latestProduct()));

    assertThat(service.recommend(currentId).sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo("이런 상품은 어떠세요?");
              // 드롭 무관 상품이므로 dropId는 null — 프런트는 상품 페이지로 보낸다.
              assertThat(section.products())
                  .singleElement()
                  .extracting(RecommendationResponse.Product::dropId)
                  .isNull();
            });
  }

  @Test
  void recommend_forDetailWhenCategoryHasOpenDrops_servesDropTitleAndSkipsLastResort() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 4)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", fallback.productId());
    verify(lastResortProductsCache, never()).get();
  }

  @Test
  void recommend_forDetailWhenLastResortCacheIsEmpty_returnsEmptyResponse() {
    UUID currentId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, null));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(lastResortProductsCache.get()).thenReturn(List.of());

    // 최후 폴백 캐시는 스케줄로 채워진다. 기동 직후엔 비어 있어 빈 응답이 될 수밖에 없다.
    assertThat(service.recommend(currentId).sections()).isEmpty();
  }

  @Test
  void recommend_forDetailWhenLastResortServed_countsFallbackAndLastResort() {
    UUID currentId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, null));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(lastResortProductsCache.get()).thenReturn(List.of(latestProduct()));

    service.recommend(currentId);

    assertThat(counterCount("recommendation.fallback", "mode", "detail", "reason", "search-failed"))
        .isEqualTo(1);
    assertThat(
            counterCount("recommendation.last-resort", "mode", "detail", "reason", "search-failed"))
        .isEqualTo(1);
  }

  @Test
  void recommend_forDetailWhenCategoryFallbackContainsCurrentProduct_excludesIt() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta other = drop(UUID.randomUUID(), categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 4))
        .thenReturn(List.of(drop(currentId, categoryId), other));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", other.productId());
  }

  @Test
  void recommend_forDetailWhenCategoryFallbackHasOnlyCurrentProduct_servesLastResort() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 4))
        .thenReturn(List.of(drop(currentId, categoryId)));
    when(lastResortProductsCache.get()).thenReturn(List.of(latestProduct()));

    assertThat(service.recommend(currentId).sections())
        .singleElement()
        .satisfies(section -> assertThat(section.title()).isEqualTo("이런 상품은 어떠세요?"));
  }

  @Test
  void recommend_forDetailWhenCurrentProductIsAmongTopCategoryDrops_stillFillsFallbackLimit() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta first = drop(UUID.randomUUID(), categoryId);
    DropMeta second = drop(UUID.randomUUID(), categoryId);
    DropMeta third = drop(UUID.randomUUID(), categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    // 현재 상품이 마감 임박 상위에 들어 있다. 상한(3)만 가져와 제외하면 2개로 언더필된다.
    when(openDropCache.findByCategory(categoryId, 4))
        .thenReturn(List.of(drop(currentId, categoryId), first, second, third));

    assertThat(service.recommend(currentId).sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.products())
                    .extracting(RecommendationResponse.Product::productId)
                    .containsExactly(first.productId(), second.productId(), third.productId()));
  }

  @Test
  void recommend_forDetailCategoryFallback_neverExceedsFallbackLimit() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    // 현재 상품이 없으면 한 개 더 받아 온 만큼이 그대로 남으므로, 상한으로 자르는지 확인한다.
    when(openDropCache.findByCategory(categoryId, 4))
        .thenReturn(
            List.of(
                drop(UUID.randomUUID(), categoryId),
                drop(UUID.randomUUID(), categoryId),
                drop(UUID.randomUUID(), categoryId),
                drop(UUID.randomUUID(), categoryId)));

    assertThat(service.recommend(currentId).sections())
        .singleElement()
        .satisfies(section -> assertThat(section.products()).hasSize(3));
  }

  @Test
  void recommend_forDetailWhenLastResortContainsCurrentProduct_excludesIt() {
    UUID currentId = UUID.randomUUID();
    RecommendationResponse.Product other = latestProduct();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, null));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(lastResortProductsCache.get())
        .thenReturn(
            List.of(
                new RecommendationResponse.Product(currentId, null, "현재", "판매자", 100L, "thumb"),
                other));

    assertFallback(service.recommend(currentId), "이런 상품은 어떠세요?", other.productId());
  }

  @Test
  void recommend_forDetailWhenEveryFallbackProductIsCurrentProduct_returnsEmptyResponse() {
    UUID currentId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, null));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(lastResortProductsCache.get())
        .thenReturn(
            List.of(new RecommendationResponse.Product(currentId, null, "현재", "판매자", 100L, "thumb")));

    // 제외 후 남는 상품이 없으면 빈 섹션을 내보내지 않고 빈 응답이 된다.
    assertThat(service.recommend(currentId).sections()).isEmpty();
  }

  @Test
  void recommend_forDetailStaleCacheHit_excludesCurrentProductWithoutRecomputing() {
    UUID currentId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "추천",
                    List.of(
                        new RecommendationResponse.Product(
                            currentId, UUID.randomUUID(), "현재", "판매자", 100L, "thumb"),
                        new RecommendationResponse.Product(
                            otherId, UUID.randomUUID(), "다른", "판매자", 200L, "thumb")))));
    when(resultCache.find("rec:detail:" + currentId)).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(currentId, otherId)))
        .thenReturn(List.of(currentId, otherId));

    assertFallback(service.recommend(currentId), "추천", otherId);
    verify(searchClient, never()).recommend(any());
    verify(llmClient, never()).complete(any());
  }

  @Test
  void recommend_forDetailStaleCacheHitWithOnlyCurrentProduct_routesToFallbackLadder() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), categoryId);
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "추천",
                    List.of(
                        new RecommendationResponse.Product(
                            currentId, UUID.randomUUID(), "현재", "판매자", 100L, "thumb")))));
    when(resultCache.find("rec:detail:" + currentId)).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(currentId))).thenReturn(List.of(currentId));
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(openDropCache.findByCategory(categoryId, 4)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", fallback.productId());
  }

  @Test
  @Timeout(10)
  void recommend_forDetailStaleCacheHitWithOnlyCurrentProduct_regeneratesInBackground()
      throws Exception {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    String key = "rec:detail:" + currentId;
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "추천",
                    List.of(
                        new RecommendationResponse.Product(
                            currentId, UUID.randomUUID(), "현재", "판매자", 100L, "thumb")))));
    ProductDetailResponse current = product(currentId, categoryId);
    when(resultCache.find(key)).thenReturn(Optional.of(cached));
    // 캐시에 남은 유일한 상품이 현재 상품이다. 드롭 자체는 아직 열려 있어 마감 필터로는 걸러지지
    // 않으므로, 열화 판정이 "현재 상품"을 반영하지 않으면 배경 재계산이 아예 시작되지 않는다.
    when(openDropCache.filterOpenProductIds(List.of(currentId))).thenReturn(List.of(currentId));
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(openDropCache.findByCategory(categoryId, 4))
        .thenReturn(List.of(drop(UUID.randomUUID(), categoryId)));
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(candidateId))));
    when(productDetailClient.getProduct(candidateId))
        .thenReturn(product(candidateId, "선택", 200L));

    service.recommend(currentId);

    // 동기 응답은 폴백이지만, 배경에서는 전체 파이프라인이 돌아 캐시가 갱신돼야 한다.
    verify(searchClient, timeout(5000)).recommend(any());
    verify(llmClient, timeout(5000)).complete("prompt");
    verify(resultCache, timeout(5000)).save(eq(key), any());
  }

  @Test
  void recommend_forHome_keepsProductsThatWouldBeExcludedInDetail() {
    UUID candidateId = UUID.randomUUID();
    DropMeta fallback = drop(candidateId, UUID.randomUUID());
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    // 홈에는 "현재 상품" 개념이 없으므로 제외가 적용되지 않는다.
    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", candidateId);
  }

  @Test
  void recommend_forDetailWhenLlmSelectsNothing_servesCategoryFallback() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), categoryId);
    ProductDetailResponse current = product(currentId, categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId))).thenReturn(List.of());
    when(openDropCache.findByCategory(categoryId, 4)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", fallback.productId());
  }

  @Test
  void recommend_forHomeWhenLlmSelectsNothing_servesGeneralFallback() {
    UUID candidateId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    stubHomeUntilPrompt(candidateId);
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId))).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
  }

  @Test
  void recommend_forHomeWhenNoSignals_servesGeneralFallback() {
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
  }

  @Test
  void recommend_forHomeWhenSearchFails_servesGeneralFallback() {
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
  }

  @Test
  void recommend_forHomeWhenNoCandidates_servesGeneralFallback() {
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(List.of());
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
    verify(llmClient, never()).complete(any());
  }

  @Test
  void recommend_forHomeWhenLlmFails_servesGeneralFallback() {
    UUID candidateId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    stubHomeUntilPrompt(candidateId);
    when(llmClient.complete("prompt")).thenThrow(new RuntimeException("llm"));
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
  }

  @Test
  void recommend_forHomeWhenSeedCollectionFails_servesGeneralFallback() {
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(seedService.collect()).thenThrow(new RuntimeException("seed collection"));
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
  }

  @Test
  void recommend_whenFallbackCacheIsEmpty_returnsEmpty() {
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    assertThat(service.recommend(null).sections()).isEmpty();
  }

  @Test
  void recommend_forDetail_doesNotFetchProductsBeyondPerSectionCap() {
    UUID currentId = UUID.randomUUID();
    List<UUID> tenIds = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tenIds.add(UUID.randomUUID());
    }
    List<SimilarProductResponse> candidates = tenIds.stream().map(this::candidate).toList();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any())).thenReturn(candidates);
    when(openDropCache.filterOpenProductIds(tenIds)).thenReturn(tenIds);
    when(promptBuilder.build(RecommendationMode.DETAIL, current, candidates)).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", tenIds))
        .thenReturn(List.of(new SelectedSection("연관", tenIds)));
    for (int i = 0; i < 6; i++) {
      UUID id = tenIds.get(i);
      when(productDetailClient.getProduct(id)).thenReturn(product(id, "상품", 200L));
    }

    var response = service.recommend(currentId);

    assertThat(response.sections())
        .singleElement()
        .satisfies(section -> assertThat(section.products()).hasSize(6));
    for (int i = 0; i < 6; i++) {
      verify(productDetailClient).getProduct(tenIds.get(i));
    }
    for (int i = 6; i < 10; i++) {
      verify(productDetailClient, never()).getProduct(tenIds.get(i));
    }
  }

  @Test
  void recommend_forHomeWhenMemberIdMalformed_degradesToAnonymousFallback() {
    UserContextHolder.set(new UserContext("not-a-uuid", Set.of("USER")));
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
    verify(resultCache, never()).find(any());
    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void recommend_forHomeWhenNoOpenDrops_returnsEmptyResponseAndSkipsLastResort() {
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    // 홈은 드롭 쇼케이스다. 빈 섹션이 프런트에서 "진행중인 드롭이 없습니다"로 보인다.
    assertThat(service.recommend(null).sections()).isEmpty();
    verify(lastResortProductsCache, never()).get();
  }

  @Test
  void recommend_forHomeWhenNoOpenDrops_stillCountsFallback() {
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    service.recommend(null);

    assertThat(counterCount("recommendation.fallback", "mode", "home", "reason", "no-seeds"))
        .isEqualTo(1);
  }

  @Test
  void recommend_forHomeWhenOpenDropsExist_servesDropSectionWithDropId() {
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    RecommendationResponse response = service.recommend(null);

    assertFallback(response, "이런 드롭은 어떠세요?", fallback.productId());
    assertThat(response.sections().get(0).products())
        .singleElement()
        .extracting(RecommendationResponse.Product::dropId)
        .isEqualTo(fallback.dropId());
  }

  @Test
  void recommend_forHome_collapsesConcurrentMissesForSameKeyIntoOnePipeline() throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.empty());
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(300);
              return List.of(candidate(id));
            });
    when(openDropCache.filterOpenProductIds(List.of(id))).thenReturn(List.of(id));
    when(promptBuilder.build(RecommendationMode.HOME, null, List.of(candidate(id)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(id)))
        .thenReturn(List.of(new SelectedSection("추천", List.of(id))));
    when(openDropCache.findByProductId(id)).thenReturn(Optional.of(drop(id, UUID.randomUUID())));

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<RecommendationResponse>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
                try {
                  start.await();
                  return service.recommend(null);
                } catch (InterruptedException exception) {
                  throw new RuntimeException(exception);
                } finally {
                  UserContextHolder.clear();
                }
              },
              pool));
    }
    start.countDown();
    for (CompletableFuture<RecommendationResponse> future : futures) {
      assertThat(future.get(5, TimeUnit.SECONDS).sections()).hasSize(1);
    }
    pool.shutdownNow();

    verify(searchClient, times(1)).recommend(any());
    verify(llmClient, times(1)).complete("prompt");
    assertThat(service.inFlightCount()).isZero();
  }

  @Test
  void recommend_whenLeaderPipelineThrowsError_releasesWaitersAndClearsInFlight()
      throws Exception {
    UUID memberId = UUID.randomUUID();
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.empty());
    // 리더가 파이프라인 안에서 RuntimeException이 아닌 Error로 죽는 상황.
    // recommendHome은 Exception만 잡으므로 Error는 singleFlight까지 전파된다.
    when(seedService.collect())
        .thenAnswer(
            invocation -> {
              Thread.sleep(200);
              throw new StackOverflowError("boom");
            });

    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          CompletableFuture.runAsync(
              () -> {
                UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
                try {
                  start.await();
                  service.recommend(null);
                } catch (InterruptedException exception) {
                  throw new RuntimeException(exception);
                } catch (Error ignored) {
                  // 리더 스레드는 Error를 그대로 되던진다(정상). 대기자는 빈 응답으로 풀린다.
                } finally {
                  UserContextHolder.clear();
                }
              },
              pool));
    }
    start.countDown();

    // 회귀(mine 미완료)면 대기자가 join()에서 영원히 막혀 여기서 TimeoutException으로 실패한다.
    for (CompletableFuture<Void> future : futures) {
      future.get(3, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    verify(seedService, times(1)).collect();
    assertThat(service.inFlightCount()).isZero();
  }

  @Test
  void recommend_whenPipelineOverloaded_shedsFallbackWithoutDownstreamCalls() {
    // 세마포어 허가 0 = 상한을 이미 초과한 상태. 모든 요청이 다운스트림을 때리지 않고 폴백으로 흐른다.
    RecommendationService saturated =
        new RecommendationService(
            seedService,
            seedScorer,
            searchClient,
            openDropCache,
            promptBuilder,
            llmClient,
            postProcessor,
            productDetailClient,
            resultCache,
            lastResortProductsCache,
            metrics,
            3,
            0,
            executor);

    RecommendationResponse response = saturated.recommend(null);

    assertThat(response.sections()).isEmpty();
    verify(searchClient, never()).recommend(any());
    verify(llmClient, never()).complete(any());
    verify(seedService, never()).collect();
  }

  @Test
  void recommend_forHomeCacheHit_removesSinceClosedDropButKeepsSection() {
    UUID memberId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    UUID closedId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "추천",
                    List.of(
                        new RecommendationResponse.Product(openId, null, "열림", "판매자", 100L, "t"),
                        new RecommendationResponse.Product(closedId, null, "마감", "판매자", 200L, "t")))));
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(openId, closedId)))
        .thenReturn(List.of(openId));

    RecommendationResponse response = service.recommend(null);

    assertThat(response.sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.products())
                    .extracting(RecommendationResponse.Product::productId)
                    .containsExactly(openId));
    // 히트 경로는 LLM/검색/시드를 절대 부르지 않는다(부분 제거는 재계산도 안 함).
    verify(llmClient, never()).complete(any());
    verify(searchClient, never()).recommend(any());
    verify(seedService, never()).collect();
  }

  @Test
  void recommend_forHomeCacheHit_dropsEmptiedSectionButServesRemaining() {
    UUID memberId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    UUID closedId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "마감그룹",
                    List.of(
                        new RecommendationResponse.Product(closedId, null, "마감", "판매자", 200L, "t"))),
                new RecommendationResponse.Section(
                    "열린그룹",
                    List.of(
                        new RecommendationResponse.Product(openId, null, "열림", "판매자", 100L, "t")))));
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(closedId, openId)))
        .thenReturn(List.of(openId));

    RecommendationResponse response = service.recommend(null);

    assertThat(response.sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo("열린그룹");
              assertThat(section.products())
                  .extracting(RecommendationResponse.Product::productId)
                  .containsExactly(openId);
            });
  }

  @Test
  void recommend_forHomeCacheHit_whenAllSectionsClosed_routesToFallbackLadder() {
    UUID memberId = UUID.randomUUID();
    UUID closedId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "마감그룹",
                    List.of(
                        new RecommendationResponse.Product(closedId, null, "마감", "판매자", 200L, "t")))));
    DropMeta fallback = drop(UUID.randomUUID(), UUID.randomUUID());
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(closedId))).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(null), "이런 드롭은 어떠세요?", fallback.productId());
  }

  @Test
  @Timeout(10)
  void recommend_forHomeCacheHit_whenSectionEmptied_regeneratesOnceWithoutBlocking()
      throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    UUID closedId = UUID.randomUUID();
    UUID regenId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "마감그룹",
                    List.of(
                        new RecommendationResponse.Product(closedId, null, "마감", "판매자", 200L, "t"))),
                new RecommendationResponse.Section(
                    "열린그룹",
                    List.of(
                        new RecommendationResponse.Product(openId, null, "열림", "판매자", 100L, "t")))));
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(closedId, openId)))
        .thenReturn(List.of(openId));

    // 배경 재계산(홈 파이프라인)이 LLM에서 블록되도록 해 in-flight 상태를 유지시킨다.
    CountDownLatch llmEntered = new CountDownLatch(1);
    CountDownLatch releaseLlm = new CountDownLatch(1);
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(regenId)));
    when(openDropCache.filterOpenProductIds(List.of(regenId))).thenReturn(List.of(regenId));
    when(promptBuilder.build(RecommendationMode.HOME, null, List.of(candidate(regenId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt"))
        .thenAnswer(
            invocation -> {
              llmEntered.countDown();
              releaseLlm.await(5, TimeUnit.SECONDS);
              return "raw";
            });
    lenient()
        .when(postProcessor.process("raw", List.of(regenId)))
        .thenReturn(List.of(new SelectedSection("추천", List.of(regenId))));
    lenient()
        .when(openDropCache.findByProductId(regenId))
        .thenReturn(Optional.of(drop(regenId, UUID.randomUUID())));

    // 1차 히트: 그룹 소멸 → 즉시 응답(LLM이 아직 안 풀렸어도 반환) + 재계산 예약.
    RecommendationResponse first = service.recommend(null);
    assertThat(first.sections())
        .singleElement()
        .satisfies(section -> assertThat(section.title()).isEqualTo("열린그룹"));

    // 배경 재계산이 LLM까지 진입했는지 확인 — 요청 스레드는 이미 반환됨(블로킹 아님).
    assertThat(llmEntered.await(5, TimeUnit.SECONDS)).isTrue();

    // 2차 히트: 같은 키 재계산이 진행 중 → 새 재계산을 시작하지 않는다(dedup).
    RecommendationResponse second = service.recommend(null);
    assertThat(second.sections())
        .singleElement()
        .satisfies(section -> assertThat(section.title()).isEqualTo("열린그룹"));

    releaseLlm.countDown();

    // 재계산은 정확히 한 번(LLM 1회) — dedup 성립.
    verify(llmClient, timeout(5000).times(1)).complete("prompt");
    verify(llmClient, times(1)).complete(any());
  }

  @Test
  void warmDetail_whenAlreadyCached_skipsPipelineAndReturnsFalse() {
    UUID productId = UUID.randomUUID();
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "연관",
                    List.of(
                        new RecommendationResponse.Product(
                            UUID.randomUUID(), null, "상품", "판매자", 100L, "t")))));
    when(resultCache.find("rec:detail:" + productId)).thenReturn(Optional.of(cached));

    assertThat(service.warmDetail(productId)).isFalse();

    // 신선한 캐시는 파이프라인(상품조회/검색/LLM)을 절대 태우지 않는다.
    verify(productDetailClient, never()).getProduct(any());
    verify(searchClient, never()).recommend(any());
    verify(llmClient, never()).complete(any());
    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void warmDetail_whenCold_runsDetailPipelineAndSavesToCache() {
    UUID currentId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    ProductDetailResponse selected = product(candidateId, "선택", 200L);
    // 실제 캐시처럼 save가 이후 find에 반영되도록 한다: warmDetail은 저장된 개인화 결과 유무로
    // true/false를 결정한다.
    java.util.concurrent.atomic.AtomicReference<RecommendationResponse> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(resultCache.find("rec:detail:" + currentId))
        .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
    doAnswer(
            invocation -> {
              saved.set(invocation.getArgument(1));
              return null;
            })
        .when(resultCache)
        .save(eq("rec:detail:" + currentId), any());
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(candidateId)));
    when(openDropCache.filterOpenProductIds(List.of(candidateId)))
        .thenReturn(List.of(candidateId));
    when(promptBuilder.build(RecommendationMode.DETAIL, current, List.of(candidate(candidateId))))
        .thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(candidateId))));
    when(productDetailClient.getProduct(candidateId)).thenReturn(selected);

    assertThat(service.warmDetail(currentId)).isTrue();

    verify(llmClient).complete("prompt");
    verify(resultCache).save(eq("rec:detail:" + currentId), any());
    assertThat(service.inFlightCount()).isZero();
  }

  @Test
  void recommend_forHomeCacheHit_withNullProductId_degradesInsteadOfThrowing() {
    UUID memberId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "추천",
                    List.of(
                        new RecommendationResponse.Product(null, null, "오염", "판매자", 100L, "t"),
                        new RecommendationResponse.Product(openId, null, "열림", "판매자", 200L, "t")))));
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(openId))).thenReturn(List.of(openId));

    RecommendationResponse response = service.recommend(null);

    // null id 상품은 걸러지고 열린 상품만 남아 200으로 나간다(NPE→500 회귀 없음).
    assertThat(response.sections())
        .singleElement()
        .satisfies(
            section ->
                assertThat(section.products())
                    .extracting(RecommendationResponse.Product::productId)
                    .containsExactly(openId));
  }

  @Test
  void recommend_whenCacheHitServingThrows_degradesToEmptyNot500() {
    UUID memberId = UUID.randomUUID();
    UUID openId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    RecommendationResponse cached =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "추천",
                    List.of(
                        new RecommendationResponse.Product(openId, null, "열림", "판매자", 100L, "t")))));
    when(resultCache.find("rec:" + memberId + ":home")).thenReturn(Optional.of(cached));
    when(openDropCache.filterOpenProductIds(List.of(openId)))
        .thenThrow(new RuntimeException("cache serve boom"));

    // 서빙 중 예외가 컨트롤러로 새지 않고 빈 응답으로 흡수된다(500 회귀 없음).
    assertThat(service.recommend(null).sections()).isEmpty();
  }

  @Test
  @Timeout(10)
  void recommend_regeneration_whenCacheAlreadyFresh_skipsPipeline() throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID closedId = UUID.randomUUID();
    UUID freshId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    String key = "rec:" + memberId + ":home";
    RecommendationResponse degraded =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "마감그룹",
                    List.of(
                        new RecommendationResponse.Product(closedId, null, "마감", "판매자", 200L, "t")))));
    RecommendationResponse fresh =
        new RecommendationResponse(
            List.of(
                new RecommendationResponse.Section(
                    "신선그룹",
                    List.of(
                        new RecommendationResponse.Product(freshId, null, "신선", "판매자", 100L, "t")))));
    CountDownLatch regenRechecked = new CountDownLatch(1);
    // 1차(요청 스레드 serveCached)는 열화된 캐시, 2차(배경 재계산 재확인)는 신선한 캐시를 본다.
    when(resultCache.find(key))
        .thenReturn(Optional.of(degraded))
        .thenAnswer(
            invocation -> {
              regenRechecked.countDown();
              return Optional.of(fresh);
            });
    // 요청 스레드: 마감그룹이 통째로 닫힘 → 열화 → 재계산 예약 + 폴백.
    when(openDropCache.filterOpenProductIds(List.of(closedId))).thenReturn(List.of());
    when(openDropCache.findGeneral(3))
        .thenReturn(List.of(drop(UUID.randomUUID(), UUID.randomUUID())));
    // 배경 재확인: fresh는 열린 상품을 가져 더 이상 열화가 아니다.
    when(openDropCache.filterOpenProductIds(List.of(freshId))).thenReturn(List.of(freshId));

    service.recommend(null);

    assertThat(regenRechecked.await(5, TimeUnit.SECONDS)).isTrue();
    // 재확인에서 신선 판정 → 파이프라인(검색/LLM/저장)을 태우지 않는다.
    verify(searchClient, after(500).never()).recommend(any());
    verify(llmClient, never()).complete(any());
    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void warmDetail_whenPipelineShedsToFallback_returnsFalse() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    when(resultCache.find("rec:detail:" + currentId)).thenReturn(Optional.empty());
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 4))
        .thenReturn(List.of(drop(UUID.randomUUID(), categoryId)));

    // 검색 실패 → 카테고리 폴백(저장 없음) → 캐시는 여전히 비어 warmDetail은 false.
    assertThat(service.warmDetail(currentId)).isFalse();
    verify(resultCache, never()).save(any(), any());
  }

  @Test
  void recommend_forDetailCacheHit_countsHitWithDetailModeTag() {
    UUID productId = UUID.randomUUID();
    RecommendationResponse cached = new RecommendationResponse(List.of());
    when(resultCache.find("rec:detail:" + productId)).thenReturn(Optional.of(cached));

    service.recommend(productId);

    assertThat(counterCount("recommendation.cache", "mode", "detail", "result", "hit"))
        .isEqualTo(1);
    assertThat(counterCount("recommendation.cache", "mode", "detail", "result", "miss")).isZero();
    assertThat(counterCount("recommendation.cache", "mode", "home", "result", "hit")).isZero();
  }

  @Test
  void recommend_forHomeNoSeeds_countsMissAndFallbackButNotPipeline() {
    UUID memberId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    when(seedService.collect()).thenReturn(List.of());
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    service.recommend(null);

    assertThat(counterCount("recommendation.cache", "mode", "home", "result", "miss")).isEqualTo(1);
    assertThat(counterCount("recommendation.fallback", "mode", "home", "reason", "no-seeds"))
        .isEqualTo(1);
    // 검색·LLM을 타지 않은 ≈0ms 요청이므로 파이프라인 타이머 표본이 아니다.
    assertThat(pipelineCount("home")).isZero();
  }

  @Test
  void recommend_whenLlmFails_stillRecordsPipelineTimer() {
    UUID candidateId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(UUID.randomUUID().toString(), Set.of("USER")));
    stubHomeUntilPrompt(candidateId);
    when(llmClient.complete("prompt")).thenThrow(new RuntimeException("llm down"));
    when(openDropCache.findGeneral(3)).thenReturn(List.of());

    service.recommend(null);

    // 실패한 파이프라인도 시간을 썼으므로 표본에 남는다.
    assertThat(pipelineCount("home")).isEqualTo(1);
    assertThat(counterCount("recommendation.fallback", "mode", "home", "reason", "llm-failed"))
        .isEqualTo(1);
  }

  @Test
  @Timeout(5)
  void recommend_whenShedByLimiter_doesNotAddPipelineSample() throws Exception {
    // 허가 1개. 첫 요청이 파이프라인 안에서 대기해 슬롯을 잡고 있는 동안 두 번째 요청이 셰딩된다.
    RecommendationService limited = serviceWithPermits(1);
    UUID heldId = UUID.randomUUID();
    UUID shedId = UUID.randomUUID();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(productDetailClient.getProduct(heldId))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(3, TimeUnit.SECONDS);
              return product(heldId, UUID.randomUUID());
            });
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(() -> limited.recommend(heldId), executor);
    assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

    limited.recommend(shedId);

    // 셰딩 시점: 표본은 아직 하나도 완료되지 않았고, 셰딩은 overloaded로만 센다.
    assertThat(pipelineCount("detail")).isZero();
    assertThat(meterRegistry.get("recommendation.overloaded").counter().count()).isEqualTo(1);
    release.countDown();
    holder.get(3, TimeUnit.SECONDS);
    // 실제로 슬롯을 잡고 돈 요청만 표본에 남는다.
    assertThat(pipelineCount("detail")).isEqualTo(1);
    assertThat(limited.inFlightCount()).isZero();
  }

  @Test
  void recommend_whenPipelineOverloaded_countsOverloadedAndFallback() {
    RecommendationService saturated = serviceWithPermits(0);

    saturated.recommend(null);

    assertThat(meterRegistry.get("recommendation.overloaded").counter().count()).isEqualTo(1);
    assertThat(counterCount("recommendation.fallback", "mode", "home", "reason", "overloaded"))
        .isEqualTo(1);
    assertThat(pipelineCount("home")).isZero();
  }

  @Test
  void recommend_forDetailWhenPipelineOverloaded_servesLastResortInsteadOfEmpty() {
    RecommendationService saturated = serviceWithPermits(0);
    RecommendationResponse.Product latest = latestProduct();
    when(lastResortProductsCache.get()).thenReturn(List.of(latest));

    assertFallback(
        saturated.recommend(UUID.randomUUID()), "이런 상품은 어떠세요?", latest.productId());
    // 셰딩의 목적은 다운스트림 보호다. 인메모리 캐시만 쓰고 상품·검색·LLM은 건드리지 않는다.
    verify(productDetailClient, never()).getProduct(any());
    verify(openDropCache, never()).findByCategory(any(), anyInt());
    verify(searchClient, never()).recommend(any());
    verify(llmClient, never()).complete(any());
  }

  @Test
  void recommend_forDetailWhenPipelineOverloaded_countsDetailFallbackAndLastResort() {
    RecommendationService saturated = serviceWithPermits(0);
    when(lastResortProductsCache.get()).thenReturn(List.of(latestProduct()));

    saturated.recommend(UUID.randomUUID());

    assertThat(meterRegistry.get("recommendation.overloaded").counter().count()).isEqualTo(1);
    assertThat(counterCount("recommendation.fallback", "mode", "detail", "reason", "overloaded"))
        .isEqualTo(1);
    assertThat(counterCount("recommendation.last-resort", "mode", "detail", "reason", "overloaded"))
        .isEqualTo(1);
    assertThat(pipelineCount("detail")).isZero();
  }

  @Test
  void warmDetail_whenPipelineOverloaded_doesNotCountRequestPathFallbackOrLastResort() {
    RecommendationService saturated = serviceWithPermits(0);
    UUID productId = UUID.randomUUID();
    when(resultCache.find("rec:detail:" + productId)).thenReturn(Optional.empty());
    when(lastResortProductsCache.get()).thenReturn(List.of(latestProduct()));

    assertThat(saturated.warmDetail(productId)).isFalse();

    // 요청 경로 시계열은 배경 셰딩으로 오염되지 않는다.
    assertThat(
            counterCountOrZero("recommendation.fallback", "mode", "detail", "reason", "overloaded"))
        .isZero();
    assertThat(
            counterCountOrZero(
                "recommendation.last-resort", "mode", "detail", "reason", "overloaded"))
        .isZero();
    // 배경 셰딩은 별도 reason과 overloaded 카운터로 여전히 관측된다.
    assertThat(
            counterCount(
                "recommendation.fallback", "mode", "detail", "reason", "overloaded-background"))
        .isEqualTo(1);
    assertThat(
            counterCount(
                "recommendation.last-resort", "mode", "detail", "reason", "overloaded-background"))
        .isEqualTo(1);
    assertThat(meterRegistry.get("recommendation.overloaded").counter().count()).isEqualTo(1);
    // 응답 조립은 요청 경로와 동일한 최후 폴백 단계를 그대로 탄다.
    verify(lastResortProductsCache).get();
    assertThat(pipelineCount("detail")).isZero();
  }

  @Test
  @Timeout(5)
  void recommend_forDetailShedWhileSemaphoreExhausted_servesLastResortSection() throws Exception {
    // 허가 1개를 실제 요청이 잡고 있는 동안 들어온 상세 요청이 셰딩된다.
    RecommendationService limited = serviceWithPermits(1);
    UUID heldId = UUID.randomUUID();
    RecommendationResponse.Product latest = latestProduct();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(productDetailClient.getProduct(heldId))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(3, TimeUnit.SECONDS);
              return product(heldId, UUID.randomUUID());
            });
    when(lastResortProductsCache.get()).thenReturn(List.of(latest));
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(() -> limited.recommend(heldId), executor);
    assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

    assertFallback(limited.recommend(UUID.randomUUID()), "이런 상품은 어떠세요?", latest.productId());
    assertThat(counterCount("recommendation.fallback", "mode", "detail", "reason", "overloaded"))
        .isEqualTo(1);

    release.countDown();
    holder.get(3, TimeUnit.SECONDS);
  }

  private RecommendationService serviceWithPermits(int maxConcurrentPipelines) {
    return new RecommendationService(
        seedService,
        seedScorer,
        searchClient,
        openDropCache,
        promptBuilder,
        llmClient,
        postProcessor,
        productDetailClient,
        resultCache,
        lastResortProductsCache,
        metrics,
        3,
        maxConcurrentPipelines,
        executor);
  }

  private double counterCount(String name, String... tags) {
    return meterRegistry.get(name).tags(tags).counter().count();
  }

  /** 아직 만들어지지 않은 시계열도 0으로 본다(증가하지 않았음을 확인할 때). */
  private double counterCountOrZero(String name, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  private long pipelineCount(String modeTag) {
    return meterRegistry.get("recommendation.pipeline").tags("mode", modeTag).timer().count();
  }

  private void stubHomeUntilPrompt(UUID id) {
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(id)));
    when(openDropCache.filterOpenProductIds(List.of(id))).thenReturn(List.of(id));
    when(promptBuilder.build(RecommendationMode.HOME, null, List.of(candidate(id)))).thenReturn("prompt");
  }

  private List<Seed> seeds() {
    return List.of(new Seed(UUID.randomUUID(), 0.3, false));
  }

  private SimilarProductResponse candidate(UUID id) {
    return new SimilarProductResponse(id, "후보", "설명", "이미지");
  }

  private ProductDetailResponse product(UUID id, String name, long price) {
    return new ProductDetailResponse(
        id, null, "판매자", name, "설명", null, null, price, "thumb", List.of(), null);
  }

  private ProductDetailResponse product(UUID id, UUID categoryId) {
    return new ProductDetailResponse(
        id, null, null, "현재", "설명", categoryId, null, 100L, "thumb", List.of(), null);
  }

  private RecommendationResponse.Product latestProduct() {
    return new RecommendationResponse.Product(
        UUID.randomUUID(), null, "최신상품", "판매자", 1000L, "thumb");
  }

  private DropMeta drop(UUID id, UUID categoryId) {
    return new DropMeta(
        UUID.randomUUID(),
        id,
        "드롭 상품",
        "판매자",
        900L,
        "thumb",
        categoryId,
        DropStatus.OPEN,
        Instant.parse("2020-01-01T00:00:00Z"),
        null);
  }

  private void assertFallback(RecommendationResponse response, String title, UUID productId) {
    assertThat(response.sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo(title);
              assertThat(section.products())
                  .singleElement()
                  .extracting(RecommendationResponse.Product::productId)
                  .isEqualTo(productId);
            });
  }
}
