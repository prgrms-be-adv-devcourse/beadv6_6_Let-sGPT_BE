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
}
