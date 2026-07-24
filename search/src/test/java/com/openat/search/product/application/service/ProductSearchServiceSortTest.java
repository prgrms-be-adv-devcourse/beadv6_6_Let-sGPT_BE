package com.openat.search.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.search.product.application.dto.ProductSearchResult;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

class ProductSearchServiceSortTest {

  private ElasticsearchOperations elasticsearchOperations;
  private ProductEmbeddingService productEmbeddingService;
  private ProductSearchService productSearchService;

  @BeforeEach
  void setUp() {
    elasticsearchOperations = mock(ElasticsearchOperations.class);
    productEmbeddingService = mock(ProductEmbeddingService.class);
    productSearchService =
        new ProductSearchService(
            elasticsearchOperations, productEmbeddingService, new SimpleMeterRegistry());
  }

  @Test
  void filtersLowRelevanceThenReturnsRelevantResultsByScore() {
    ProductDocument newestIrrelevant =
        document("newest-irrelevant", 1_000L, "2026-03-01T00:00:00Z");
    ProductDocument newerRelevant =
        document("newer-relevant", 1_000L, "2026-02-01T00:00:00Z");
    ProductDocument mostRelevant = document("most-relevant", 1_000L, "2026-01-01T00:00:00Z");

    when(productEmbeddingService.embed("bag")).thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(
            List.of(newestIrrelevant, newerRelevant, mostRelevant),
            List.of(0.60F, 0.90F, 0.95F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("bag", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("most-relevant", "newer-relevant");
  }

  @Test
  void keepsSemanticVectorCandidatesWithoutLiteralQueryTerms() {
    ProductDocument semanticMatch =
        document("semantic", "초경량 크로스백", "작은 소지품을 휴대하기 좋은 제품", null);

    when(productEmbeddingService.embed("여행할 때 편하게 메는 작은 가방"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(semanticMatch), List.of(0.92F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("여행할 때 편하게 메는 작은 가방", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("semantic");
  }

  @Test
  void keepsExplicitBlackColorMatchAheadOfHigherScoringBrownProduct() {
    ProductDocument brownShoes =
        document(
            "brown-shoes",
            "가죽 옥스퍼드 슈즈",
            "단정한 남성 구두",
            "브라운, 갈색, 카멜, 매끄러운 가죽");
    ProductDocument blackShoes =
        document(
            "black-shoes",
            "가죽 더비 슈즈",
            "단정한 남성 정장 구두",
            "검정색, 블랙, 무광 가죽, 끈 있는 디자인");

    when(productEmbeddingService.embed("정장에 어울리는 검은색 가죽 소재의 단정한 남성 구두"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(brownShoes, blackShoes), List.of(0.99F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search(
            "정장에 어울리는 검은색 가죽 소재의 단정한 남성 구두", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-shoes");
  }

  @Test
  void excludesBeltWhenQueryExplicitlyRequestsDressShoes() {
    ProductDocument blackShoes =
        document(
            "black-shoes",
            "가죽 더비 슈즈",
            "단정한 남성 정장 구두",
            "검정색, 블랙, 무광 가죽, 끈 있는 디자인");
    ProductDocument leatherBelt =
        document(
            "leather-belt",
            "아틀리에 가죽 벨트",
            "클래식한 정장용 가죽 액세서리",
            "브라운, 갈색, 은색 버클, 단정한 디자인");

    when(productEmbeddingService.embed("정장에 어울리는 검은색 가죽 소재의 단정한 남성 구두"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(blackShoes, leatherBelt), List.of(0.90F, 0.89F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search(
            "정장에 어울리는 검은색 가죽 소재의 단정한 남성 구두", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-shoes");
  }

  @Test
  void returnsEmptyWhenNoCandidateMatchesExplicitDressShoesType() {
    ProductDocument leatherBelt =
        document(
            "leather-belt",
            "아틀리에 가죽 벨트",
            "클래식한 정장용 가죽 액세서리",
            "브라운, 갈색, 은색 버클, 단정한 디자인");

    when(productEmbeddingService.embed("검은색 남성 구두"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(leatherBelt), List.of(0.95F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검은색 남성 구두", null, null, null, 0, 20);

    assertThat(result.getContent()).isEmpty();
  }

  @Test
  void doesNotInterpretSilverSubstringInsideBlackAsGray() {
    ProductDocument blackBag =
        document(
            "black-bag",
            "검정 가죽 가방",
            "단정한 비즈니스 가방",
            "검정색, 블랙, 가죽 소재");
    ProductDocument brownBelt =
        document(
            "brown-belt",
            "가죽 벨트",
            "단정한 정장용 액세서리",
            "브라운, 갈색, 은색 버클");

    when(productEmbeddingService.embed("검은색 가죽 소재"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(brownBelt, blackBag), List.of(0.99F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검은색 가죽 소재", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-bag");
  }

  @Test
  void keepsOnlyBootProductsWhenQueryExplicitlyRequestsBoots() {
    ProductDocument wheelchair =
        document(
            "wheelchair",
            "아웃도어 이동 용품",
            "비 오는 날 사용할 수 있는 이동 장비",
            "검정색 전동 휠체어, 방수 소재 커버, 대형 타이어");
    ProductDocument dressShoes =
        document(
            "dress-shoes",
            "가죽 옥스퍼드 슈즈",
            "단정한 남성 구두",
            "검정색 더비 슈즈, 가죽 소재, 낮은 굽");
    ProductDocument blackBoots =
        document(
            "black-boots",
            "검정 첼시 부츠",
            "비 오는 날 신기 좋은 신발",
            "블랙 첼시 부츠, 방수 가죽, 고무 밑창");

    when(productEmbeddingService.embed("비 오는 날에도 신을 수 있는 검은색 방수 아웃도어 부츠"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(
            List.of(wheelchair, dressShoes, blackBoots), List.of(0.99F, 0.95F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search(
            "비 오는 날에도 신을 수 있는 검은색 방수 아웃도어 부츠",
            null,
            null,
            null,
            0,
            20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-boots");
  }

  @Test
  void prioritizesCrossbodyProductTypeOverRequestedColor() {
    ProductDocument beigeChair =
        document(
            "beige-chair",
            "베이지 디자인 체어",
            "작은 공간에 놓기 좋은 의자",
            "베이지색 소형 의자, 패브릭 소재, 수납 공간");
    ProductDocument beigeStorageBox =
        document(
            "beige-storage-box",
            "베이지 수납 박스",
            "여행용 소품을 정리하는 수납함",
            "베이지색 소형 수납함, 손잡이 포함");
    ProductDocument crossbodyBag =
        document(
            "crossbody-bag",
            "미니멀 크로스백",
            "여행할 때 간편하게 메는 소형 가방",
            "검정색 크로스백, 긴 어깨 스트랩, 지퍼 포켓");

    when(productEmbeddingService.embed("여행할 때 간편하게 멜 수 있는 베이지색 소형 크로스백"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(
            List.of(beigeChair, beigeStorageBox, crossbodyBag),
            List.of(0.99F, 0.97F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search(
            "여행할 때 간편하게 멜 수 있는 베이지색 소형 크로스백",
            null,
            null,
            null,
            0,
            20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("crossbody-bag");
  }

  @Test
  void keepsRequestedColorWithinMatchingCrossbodyProducts() {
    ProductDocument blackCrossbodyBag =
        document(
            "black-crossbody-bag",
            "미니멀 크로스백",
            "여행용 소형 가방",
            "검정색 크로스백, 긴 어깨 스트랩");
    ProductDocument beigeCrossbodyBag =
        document(
            "beige-crossbody-bag",
            "데일리 크로스바디 백",
            "가볍게 메는 소형 가방",
            "베이지색 크로스백, 조절식 스트랩");

    when(productEmbeddingService.embed("베이지색 소형 크로스백"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(
            List.of(blackCrossbodyBag, beigeCrossbodyBag), List.of(0.99F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("베이지색 소형 크로스백", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("beige-crossbody-bag");
  }

  @Test
  void usesMostSpecificProductTypeFromQuery() {
    ProductDocument laptop =
        document(
            "laptop", "업무용 노트북", "휴대용 컴퓨터", "노트북 컴퓨터, 금속 본체");
    ProductDocument genericBag =
        document("generic-bag", "여행 가방", "소지품 수납", "캔버스 가방, 손잡이");
    ProductDocument laptopPouch =
        document(
            "laptop-pouch",
            "노트북 파우치",
            "기기 보호용 수납 제품",
            "노트북 슬리브, 지퍼 잠금");

    when(productEmbeddingService.embed("여행용 노트북 파우치 가방"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(laptop, genericBag, laptopPouch), List.of(0.99F, 0.98F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("여행용 노트북 파우치 가방", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("laptop-pouch");
  }

  @Test
  void doesNotClassifyTableLampAsFurnitureTable() {
    ProductDocument tableLamp =
        document(
            "table-lamp", "원목 테이블 램프", "침실용 조명", "테이블 램프, 패브릭 갓");
    ProductDocument table =
        document(
            "table", "원목 사이드 테이블", "거실용 가구", "원형 목재 테이블, 나무 다리");

    when(productEmbeddingService.embed("거실에 놓을 원목 테이블"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(tableLamp, table), List.of(0.99F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("거실에 놓을 원목 테이블", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("table");
  }

  @Test
  void genericBagQueryIncludesSpecificBagTypes() {
    ProductDocument crossbodyBag =
        document(
            "crossbody-bag",
            "미니멀 크로스백",
            "여행용 소형 가방",
            "크로스백, 어깨 스트랩");

    when(productEmbeddingService.embed("가볍게 멜 수 있는 가방"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(crossbodyBag), List.of(0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("가볍게 멜 수 있는 가방", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("crossbody-bag");
  }

  @Test
  void usesDescriptionAsProductTypeFallbackWhenPrimaryMetadataHasNoType() {
    ProductDocument unnamedBag =
        document("unnamed-bag", "종우", "블랙 가방", "검은색, 방수 원단, 지퍼 포켓");
    ProductDocument chair =
        document("chair", "디자인 체어", "거실 의자", "베이지색, 패브릭 소재");

    when(productEmbeddingService.embed("검은색 가방"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(chair, unnamedBag), List.of(0.99F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검은색 가방", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("unnamed-bag");
  }

  @Test
  void recognizesRedColorSynonymInProductDescription() {
    ProductDocument blueCarrier =
        document("blue", "여행용 캐리어", "바퀴 달린 수하물", "파란색, 블루, 네이비");
    ProductDocument redCarrier =
        document("red", "여행용 캐리어", "바퀴 달린 수하물", "레드, 붉은색, 크림슨");

    when(productEmbeddingService.embed("빨간색 여행용 캐리어"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(blueCarrier, redCarrier), List.of(0.99F, 0.85F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("빨간색 여행용 캐리어", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("red");
  }

  @Test
  void usesLexicalMatchAsSmallRerankSignalForRelevantVectorCandidates() {
    ProductDocument semanticOnly =
        document("semantic-only", "데일리 스니커즈", "가벼운 신발", null);
    ProductDocument lexicalMatch =
        document("lexical-match", "러닝 운동화", "러닝용 신발", null);

    when(productEmbeddingService.embed("러닝 운동화"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(semanticOnly, lexicalMatch), List.of(0.98F, 0.90F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("러닝 운동화", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("lexical-match", "semantic-only");
  }

  @Test
  void ranksProductsByElasticsearchRescoredKnnScore() {
    ProductDocument fartherProduct =
        document("farther", "검정색 운동화", "러닝 신발", null).withEmbedding(new float[] {0.0F, 1.0F});
    ProductDocument nearestProduct =
        document("nearest", "검정색 운동화", "러닝 신발", null).withEmbedding(new float[] {0.99F, 0.01F});

    when(productEmbeddingService.embed("검정색 운동화"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(fartherProduct, nearestProduct), List.of(0.88F, 0.95F));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검정색 운동화", null, null, null, 0, 20);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("nearest", "farther");
    assertThat(result.getContent().get(0).score())
        .isGreaterThan(result.getContent().get(1).score());
  }

  @Test
  void searchesWideCandidatePoolAndRescoresOriginalVectors() {
    when(productEmbeddingService.embed("검정색 운동화"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = emptySearchHits();
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    productSearchService.search("검정색 운동화", null, null, null, 0, 20);

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDocument.class));
    var knn = queryCaptor.getValue().getKnnSearches().getFirst();

    assertThat(knn.k()).isEqualTo(100);
    assertThat(knn.numCandidates()).isEqualTo(1_000);
    assertThat(knn.rescoreVector().oversample()).isEqualTo(5.0F);
    assertThat(queryCaptor.getValue().getSourceFilter().getIncludes()).doesNotContain("embedding");
  }

  @Test
  void buildsMatchAllActiveProductQueryAndKeepsLatestSortAsDefaultWhenQueryIsMissing() {
    SearchHits<ProductDocument> searchHits = emptySearchHits();
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(searchHits);

    productSearchService.search(null, null, null, null, 0, 20);

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDocument.class));
    NativeQuery nativeQuery = queryCaptor.getValue();
    Sort.Order order = nativeQuery.getPageable().getSort().getOrderFor("createdAt");

    assertThat(nativeQuery.getQuery().isBool()).isTrue();
    assertThat(nativeQuery.getQuery().bool().filter()).singleElement();
    assertThat(nativeQuery.getQuery().bool().filter().getFirst().bool().mustNot())
        .singleElement()
        .satisfies(query -> assertThat(query.exists().field()).isEqualTo("deletedAt"));
    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  private SearchHits<ProductDocument> emptySearchHits() {
    SearchHits<ProductDocument> searchHits = mock(SearchHits.class);
    when(searchHits.stream()).thenReturn(Stream.empty());
    when(searchHits.getTotalHits()).thenReturn(0L);
    return searchHits;
  }

  private SearchHits<ProductDocument> searchHits(List<ProductDocument> documents) {
    SearchHits<ProductDocument> searchHits = mock(SearchHits.class);
    List<SearchHit<ProductDocument>> hits = documents.stream().map(this::searchHit).toList();
    when(searchHits.stream()).thenAnswer(ignored -> hits.stream());
    return searchHits;
  }

  private SearchHits<ProductDocument> searchHits(
      List<ProductDocument> documents, List<Float> scores) {
    SearchHits<ProductDocument> searchHits = mock(SearchHits.class);
    List<SearchHit<ProductDocument>> hits =
        java.util.stream.IntStream.range(0, documents.size())
            .mapToObj(index -> searchHit(documents.get(index), scores.get(index)))
            .toList();
    when(searchHits.stream()).thenAnswer(ignored -> hits.stream());
    return searchHits;
  }

  private SearchHit<ProductDocument> searchHit(ProductDocument document) {
    return searchHit(document, 0.5F);
  }

  private SearchHit<ProductDocument> searchHit(ProductDocument document, float score) {
    SearchHit<ProductDocument> searchHit = mock(SearchHit.class);
    when(searchHit.getContent()).thenReturn(document);
    when(searchHit.getScore()).thenReturn(score);
    return searchHit;
  }

  private ProductDocument document(String id, Long price, String createdAt) {
    return new ProductDocument(
        id,
        "bag " + id,
        "bag description",
        null,
        null,
        null,
        price,
        null,
        null,
        new float[] {1.0F, 0.0F},
        Instant.parse(createdAt),
        null,
        null);
  }

  private ProductDocument document(
      String id, String name, String description, String imgDescription) {
    return new ProductDocument(
        id,
        name,
        description,
        null,
        null,
        null,
        1_000L,
        null,
        imgDescription,
        new float[] {1.0F, 0.0F},
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        null);
  }
}
