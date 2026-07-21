package com.openat.search.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

  @ParameterizedTest
  @CsvSource({
    "'createdAt,desc', createdAt, DESC",
    "'price,asc', price, ASC",
    "'price,desc', price, DESC"
  })
  void appliesRequestedSortToFilterSearch(
      String sortValue, String expectedProperty, Sort.Direction expectedDirection) {
    SearchHits<ProductDocument> searchHits = emptySearchHits();
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(searchHits);

    productSearchService.search(null, null, null, null, 0, 20, sortValue);

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDocument.class));
    Sort.Order order = queryCaptor.getValue().getPageable().getSort().getOrderFor(expectedProperty);

    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(expectedDirection);
  }

  @ParameterizedTest
  @CsvSource({
    "'createdAt,desc', newest, middle, oldest",
    "'price,asc', cheapest, middle, expensive",
    "'price,desc', expensive, middle, cheapest"
  })
  void sortsVectorSearchResultsByRequestedSort(
      String sortValue, String firstId, String secondId, String thirdId) {
    ProductDocument oldest = document("oldest", 2_000L, "2026-01-01T00:00:00Z");
    ProductDocument newest = document("newest", 3_000L, "2026-03-01T00:00:00Z");
    ProductDocument middle = document("middle", 2_000L, "2026-02-01T00:00:00Z");
    ProductDocument cheapest = document("cheapest", 1_000L, "2026-01-15T00:00:00Z");
    ProductDocument expensive = document("expensive", 3_000L, "2026-02-15T00:00:00Z");

    List<ProductDocument> documents =
        sortValue.startsWith("createdAt")
            ? List.of(oldest, newest, middle)
            : List.of(middle, expensive, cheapest);

    when(productEmbeddingService.embed("bag")).thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> searchHits = searchHits(documents);
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(searchHits);

    Page<ProductSearchResult> result =
        productSearchService.search("bag", null, null, null, 0, 20, sortValue);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly(firstId, secondId, thirdId);
  }

  @Test
  void keepsOnlyVectorCandidatesContainingAllTermsInSameAttributeContext() {
    ProductDocument bothTermsInName = document("both-in-name", "가벼운 흰색 운동화", "러닝용 신발", null);
    ProductDocument termsAcrossFields = document("across-fields", "흰색 러닝화", "편안한 운동화", null);
    ProductDocument onlyColor = document("only-color", "흰색 반팔티", "여름 의류", null);
    ProductDocument onlyProduct = document("only-product", "검은색 운동화", "러닝용 신발", null);

    when(productEmbeddingService.embed("흰색 운동화")).thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(bothTermsInName, termsAcrossFields, onlyColor, onlyProduct));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("흰색 운동화", null, null, null, 0, 20, null);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("both-in-name");
  }

  @Test
  void excludesAccessoryColorFromPrimaryProductColorMatch() {
    ProductDocument burgundyDerbyWithBlackLaces =
        document(
            "burgundy-derby",
            "프리미엄 운동화",
            "수제화 스타일",
            "버건디 스웨이드 윙팁 더비 슈즈, 브로깅 디테일, 검정색 끈, 원형 코, 갈색 밑창");
    ProductDocument blackSneakers =
        document("black-sneakers", "데일리 스니커즈", "가벼운 신발", "검정색 운동화, 메시 소재, 흰색 밑창, 검정색 끈");

    when(productEmbeddingService.embed("검정색 운동화"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates =
        searchHits(List.of(burgundyDerbyWithBlackLaces, blackSneakers));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검정색 운동화", null, null, null, 0, 20, null);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-sneakers");
  }

  @Test
  void includesStandalonePrimaryColorWithProductTypeFromFirstAttribute() {
    ProductDocument blackLoafers =
        document(
            "black-loafers",
            "클래식 남성화",
            "편안한 데일리 슈즈",
            "가죽 로퍼, 블랙, 검정색, 다크그레이, 매트한 가죽, 끈 장식, 태슬 포인트, "
                + "스티치 디테일, 편안한 디자인, 남성 신발, 데일리 슈즈");

    when(productEmbeddingService.embed("검정 로퍼"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(blackLoafers));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검정 로퍼", null, null, null, 0, 20, null);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-loafers");
  }

  @ParameterizedTest
  @ValueSource(strings = {"검정 침구", "검정 베개"})
  void matchesPrimaryColorWithProductTermsFromLaterImageAttributes(String queryText) {
    ProductDocument blackBedding =
        document(
            "black-bedding",
            "포근한 패딩 세트",
            "재활용 소재로 만든 침실용 제품",
            "검정, 다크그레이(dark grey), 차콜(charcoal) 색상 침구 세트, 직사각형 베개, "
                + "폴리에스터 리사이클 나일론 소재, 매끄러운 광택 질감, 바스락거리는 패딩 형태, "
                + "포근한 볼륨감, 수평으로 쌓인 구도");

    when(productEmbeddingService.embed(queryText))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(blackBedding));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search(queryText, null, null, null, 0, 20, null);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("black-bedding");
  }

  @Test
  void doesNotMatchProductNameInsideLongerCompoundAttribute() {
    ProductDocument brownBarStool =
        document(
            "brown-bar-stool",
            "클래식 바 스툴",
            "견고한 원목 의자",
            "바 스툴, 나무 소재, 짙은 갈색, 브라운, 다크 우드, 패브릭 시트, 네이비, 남색, "
                + "청색, 사각형 쿠션, 금속 장식 스테드, 발받침대, 견고한 구조");

    when(productEmbeddingService.embed("브라운 침대")).thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    when(productEmbeddingService.embed("브라운 발받침대"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(brownBarStool));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> bedResult =
        productSearchService.search("브라운 침대", null, null, null, 0, 20, null);
    Page<ProductSearchResult> footrestResult =
        productSearchService.search("브라운 발받침대", null, null, null, 0, 20, null);

    assertThat(bedResult.getContent()).isEmpty();
    assertThat(footrestResult.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("brown-bar-stool");
  }

  @Test
  void excludesCandidateWhenSingleCharacterKoreanAttributeIsMissing() {
    ProductDocument slipOnSneakersWithoutLaces =
        document(
            "slip-on-without-laces",
            "메쉬 슬립온",
            "갑피 일체형 신발",
            "슬립온 운동화, 검정색(블랙), 짙은회색(차콜), 빨간색(레드) 포인트, 메쉬 소재, "
                + "니트 질감, 갑피 일체형, 신축성 있는 소재, 둥근 코, 낮은 굽, 스웨이브 러빙 스타일");

    when(productEmbeddingService.embed("블랙 끈"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(slipOnSneakersWithoutLaces));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("블랙 끈", null, null, null, 0, 20, null);

    assertThat(result.getContent()).isEmpty();
  }

  @Test
  void doesNotMatchSingleKoreanSyllableInsideCompoundWord() {
    ProductDocument socksAndSneakers =
        document(
            "socks-and-sneakers",
            "블랙 로우탑 운동화",
            "고탄성 니트 메쉬 신발",
            "검정, 블랙, 다크그레이 스니커즈, 양말, 신발, 니트 소재, 메쉬 질감");
    ProductDocument horseToy =
        document("horse-toy", "말 인형", "어린이 장난감", "갈색 말, 부드러운 봉제 소재");

    when(productEmbeddingService.embed("말"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(socksAndSneakers, horseToy));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("말", null, null, null, 0, 20, null);

    assertThat(result.getContent())
        .extracting(item -> item.document().id())
        .containsExactly("horse-toy");
  }

  @Test
  void reranksMatchingProductsByExactCosineSimilarity() {
    ProductDocument fartherProduct =
        document("farther", "검정색 운동화", "러닝 신발", null).withEmbedding(new float[] {0.0F, 1.0F});
    ProductDocument nearestProduct =
        document("nearest", "검정색 운동화", "러닝 신발", null).withEmbedding(new float[] {0.99F, 0.01F});

    when(productEmbeddingService.embed("검정색 운동화"))
        .thenReturn(Optional.of(new float[] {1.0F, 0.0F}));
    SearchHits<ProductDocument> candidates = searchHits(List.of(fartherProduct, nearestProduct));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(candidates);

    Page<ProductSearchResult> result =
        productSearchService.search("검정색 운동화", null, null, null, 0, 20, null);

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

    productSearchService.search("검정색 운동화", null, null, null, 0, 20, null);

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDocument.class));
    var knn = queryCaptor.getValue().getKnnSearches().getFirst();

    assertThat(knn.k()).isEqualTo(500);
    assertThat(knn.numCandidates()).isEqualTo(5_000);
    assertThat(knn.rescoreVector().oversample()).isEqualTo(5.0F);
  }

  @Test
  void buildsMatchAllActiveProductQueryAndKeepsLatestSortAsDefaultWhenQueryIsMissing() {
    SearchHits<ProductDocument> searchHits = emptySearchHits();
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(searchHits);

    productSearchService.search(null, null, null, null, 0, 20, null);

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

  @Test
  void rejectsUnsupportedSort() {
    assertThatThrownBy(
            () -> productSearchService.search(null, null, null, null, 0, 20, "updatedAt,desc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("createdAt,desc");
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

  private SearchHit<ProductDocument> searchHit(ProductDocument document) {
    SearchHit<ProductDocument> searchHit = mock(SearchHit.class);
    when(searchHit.getContent()).thenReturn(document);
    when(searchHit.getScore()).thenReturn(0.5F);
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
