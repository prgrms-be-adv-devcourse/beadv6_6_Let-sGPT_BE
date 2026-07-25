package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import com.openat.recommendation.application.port.out.LlmClient;
import com.openat.recommendation.application.service.RecommendationPostProcessor.SelectedSection;
import com.openat.recommendation.domain.model.DropMeta;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.domain.service.SeedScorer;
import com.openat.recommendation.infrastructure.cache.RecommendationResultCache;
import com.openat.recommendation.infrastructure.client.ProductDetailClient;
import com.openat.recommendation.infrastructure.client.ProductDetailClient.ProductDetailResponse;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient;
import com.openat.recommendation.infrastructure.client.SearchRecommendClient.SimilarProductResponse;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

  @Mock RecommendationSeedService seedService;
  @Spy
  SeedScorer seedScorer =
      new SeedScorer(0.3, 0.5, 0.1, 0.85, 10, 10);
  @Mock SearchRecommendClient searchClient;
  @Mock OpenDropCache openDropCache;
  @Mock RecommendationPromptBuilder promptBuilder;
  @Mock LlmClient llmClient;
  @Mock RecommendationPostProcessor postProcessor;
  @Mock ProductDetailClient productDetailClient;
  @Mock RecommendationResultCache resultCache;

  private RecommendationService service;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newFixedThreadPool(4);
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
            3,
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
    when(resultCache.find("rec:detail:" + productId))
        .thenReturn(Optional.of(cached));

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
            3,
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
    when(promptBuilder.build(current, List.of(candidate(candidateId)))).thenReturn("prompt");
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
    verify(openDropCache, never()).filterOpenProductIds(any());
    verify(seedService, never()).collect();
    verify(seedScorer).currentProductSeed(currentId);
  }

  @Test
  void recommend_forDetail_fetchesProductsConcurrentlyAndPreservesSelectionOrder() throws Exception {
    UUID currentId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    ProductDetailResponse current = product(currentId, "현재", 100L);
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    when(productDetailClient.getProduct(currentId)).thenReturn(current);
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(firstId), candidate(secondId)));
    when(promptBuilder.build(current, List.of(candidate(firstId), candidate(secondId))))
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
    when(promptBuilder.build(current, List.of(candidate(otherId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(otherId))).thenReturn(List.of());

    service.recommend(currentId);

    verify(promptBuilder).build(current, List.of(candidate(otherId)));
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
    when(promptBuilder.build(current, List.of(candidate(purchasedId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(purchasedId)))
        .thenReturn(List.of(new SelectedSection("연관", List.of(purchasedId))));
    when(productDetailClient.getProduct(purchasedId)).thenReturn(selected);

    var response = service.recommend(currentId);

    verify(promptBuilder).build(current, List.of(candidate(purchasedId)));
    verify(postProcessor).process("raw", List.of(purchasedId));
    verify(seedService, never()).collect();
    assertThat(response.sections())
        .flatExtracting(RecommendationResponse.Section::products)
        .extracting(RecommendationResponse.Product::productId)
        .containsExactly(purchasedId);
  }

  @Test
  void recommend_forHome_capsSectionsAtThreeAndProductsAtFourPerSectionAndTwelveTotal() {
    List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(16).toList();
    List<SimilarProductResponse> candidates = ids.stream().map(this::candidate).toList();
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(candidates);
    when(openDropCache.filterOpenProductIds(ids)).thenReturn(ids);
    when(promptBuilder.build(null, candidates)).thenReturn("prompt");
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
    assertThat(response.sections()).allSatisfy(section -> assertThat(section.products()).hasSize(4));
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
    when(promptBuilder.build(current, candidates)).thenReturn("prompt");
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
    when(promptBuilder.build(null, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId))).thenReturn(List.of());

    service.recommend(null);

    verify(promptBuilder).build(null, List.of(candidate(candidateId)));
  }

  @Test
  void recommend_forDetailWhenSearchFails_servesCategoryFallback() {
    UUID currentId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta fallback = drop(UUID.randomUUID(), categoryId);
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, categoryId));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));
    when(openDropCache.findByCategory(categoryId, 3)).thenReturn(List.of(fallback));

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
    when(promptBuilder.build(current, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenThrow(new RuntimeException("llm"));
    when(openDropCache.findByCategory(categoryId, 3)).thenReturn(List.of(fallback));

    assertFallback(service.recommend(currentId), "이 카테고리의 다른 드롭", fallback.productId());
  }

  @Test
  void recommend_forDetail_usesOnlyCurrentProductSeedWhenLoggedIn() {
    UUID currentId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, UUID.randomUUID()));
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
  void recommend_forDetailWhenCategoryIsNull_returnsEmptyFallback() {
    UUID currentId = UUID.randomUUID();
    when(productDetailClient.getProduct(currentId)).thenReturn(product(currentId, null));
    when(searchClient.recommend(any())).thenThrow(new RuntimeException("search"));

    assertThat(service.recommend(currentId).sections()).isEmpty();
    verify(openDropCache, never()).findByCategory(any(), anyInt());
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
    when(promptBuilder.build(current, List.of(candidate(candidateId)))).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", List.of(candidateId))).thenReturn(List.of());
    when(openDropCache.findByCategory(categoryId, 3)).thenReturn(List.of(fallback));

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
    when(promptBuilder.build(current, candidates)).thenReturn("prompt");
    when(llmClient.complete("prompt")).thenReturn("raw");
    when(postProcessor.process("raw", tenIds))
        .thenReturn(List.of(new SelectedSection("연관", tenIds)));
    for (int i = 0; i < 6; i++) {
      UUID id = tenIds.get(i);
      when(productDetailClient.getProduct(id)).thenReturn(product(id, "상품", 200L));
    }

    var response = service.recommend(currentId);

    assertThat(response.sections()).singleElement().satisfies(
        section -> assertThat(section.products()).hasSize(6));
    for (int i = 0; i < 6; i++) {
      verify(productDetailClient).getProduct(tenIds.get(i));
    }
    for (int i = 6; i < 10; i++) {
      verify(productDetailClient, never()).getProduct(tenIds.get(i));
    }
  }

  private void stubHomeUntilPrompt(UUID id) {
    when(seedService.collect()).thenReturn(seeds());
    when(searchClient.recommend(any())).thenReturn(List.of(candidate(id)));
    when(openDropCache.filterOpenProductIds(List.of(id))).thenReturn(List.of(id));
    when(promptBuilder.build(null, List.of(candidate(id)))).thenReturn("prompt");
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

  private DropMeta drop(UUID id, UUID categoryId) {
    return new DropMeta(
        UUID.randomUUID(),
        id,
        "드롭 상품",
        "판매자",
        900L,
        "thumb",
        categoryId,
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
