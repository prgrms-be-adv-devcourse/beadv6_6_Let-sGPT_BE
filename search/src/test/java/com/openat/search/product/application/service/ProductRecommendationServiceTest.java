package com.openat.search.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.presentation.dto.ProductRecommendationRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

@ExtendWith(OutputCaptureExtension.class)
class ProductRecommendationServiceTest {

  private static final String ID_1 = "20ae1988-24ec-3c83-8d5b-e973aeab350d";
  private static final String ID_2 = "f5bbf8d7-0d08-3abf-ae30-23402c7137f7";
  private static final String ID_3 = "e12487e1-045d-3c48-8805-fdc3a9f3f5fd";
  private static final String RESULT_ID = "2d3d3c1f-e544-40ca-a04f-6ad75413f03e";

  private ElasticsearchOperations elasticsearchOperations;
  private ElasticsearchClient elasticsearchClient;
  private ProductRecommendationService service;

  @BeforeEach
  void setUp() {
    elasticsearchOperations = mock(ElasticsearchOperations.class);
    elasticsearchClient = mock(ElasticsearchClient.class);
    service = new ProductRecommendationService(elasticsearchOperations, elasticsearchClient);
  }

  @Test
  void searchesWithWeightedVectorSumAndExcludesPurchasedSeedProducts(CapturedOutput output)
      throws IOException {
    mockSeedProducts(
        List.of(
            document(ID_1, embedding(1.0F, 2.0F)),
            document(ID_2, embedding(3.0F, 4.0F)),
            document(ID_3, embedding(5.0F, 6.0F))));
    ProductDocument resultDocument = document(RESULT_ID, null);
    SearchHits<ProductDocument> hits = searchHits(resultDocument);
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(ProductDocument.class)))
        .thenReturn(hits);

    var result =
        service.recommend(
            new ProductRecommendationRequest(
                ID_1 + "|" + ID_2 + "|" + ID_3, "0.5|0.4|0.2", "T|F|T", 7));

    assertThat(result)
        .singleElement()
        .satisfies(
            product -> {
              assertThat(product.id().toString()).isEqualTo(RESULT_ID);
              assertThat(product.name()).isEqualTo("name-" + RESULT_ID);
              assertThat(product.description()).isEqualTo("description-" + RESULT_ID);
              assertThat(product.imgDescription()).isEqualTo("image-" + RESULT_ID);
            });

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations).search(queryCaptor.capture(), eq(ProductDocument.class));
    var knn = queryCaptor.getValue().getKnnSearches().getFirst();

    assertThat(knn.field()).isEqualTo("embedding");
    assertThat(knn.k()).isEqualTo(7);
    assertThat(knn.numCandidates()).isEqualTo(100);
    assertThat(queryCaptor.getValue().getPageable().getPageSize()).isEqualTo(7);
    assertThat(knn.queryVector()).hasSize(1536);
    assertThat(knn.queryVector().subList(0, 2)).containsExactly(2.7F, 3.8F);
    assertThat(knn.queryVector().subList(2, 1536)).containsOnly(0.0F);
    assertThat(knn.filter()).hasSize(2);
    assertThat(knn.filter().get(1).bool().mustNot().getFirst().ids().values())
        .containsExactlyInAnyOrder(ID_1, ID_3)
        .doesNotContain(ID_2);

    ArgumentCaptor<SearchRequest> seedQueryCaptor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(elasticsearchClient).search(seedQueryCaptor.capture(), eq(ProductDocument.class));
    assertThat(seedQueryCaptor.getValue().source().filter().excludeVectors()).isFalse();
    assertThat(seedQueryCaptor.getValue().source().filter().includes()).contains("embedding");
    assertThat(output)
        .contains(ID_1 + " -> dense_vector * [0.5]")
        .contains(ID_2 + " -> dense_vector * [0.4]")
        .contains(ID_3 + " -> dense_vector * [0.2]")
        .contains("= dense_vector 합산 [2.7, 3.8");
  }

  @Test
  void rejectsDifferentNumbersOfIdsScoresAndBuyFlags() {
    ProductRecommendationRequest request =
        new ProductRecommendationRequest(ID_1 + "|" + ID_2, "0.5", "T|F", null);

    assertThatThrownBy(() -> service.recommend(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
              assertThat(exception.getMessage()).contains("same number");
            });
    verifyNoInteractions(elasticsearchOperations);
    verifyNoInteractions(elasticsearchClient);
  }

  @Test
  void rejectsSizeOutsideSupportedRange() {
    ProductRecommendationRequest request = new ProductRecommendationRequest(ID_1, "0.5", "F", 101);

    assertThatThrownBy(() -> service.recommend(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
              assertThat(exception.getMessage()).contains("size must be between 1 and 100");
            });
    verifyNoInteractions(elasticsearchOperations);
    verifyNoInteractions(elasticsearchClient);
  }

  private void mockSeedProducts(List<ProductDocument> documents) throws IOException {
    SearchResponse<ProductDocument> response = mock(SearchResponse.class);
    HitsMetadata<ProductDocument> hitsMetadata = mock(HitsMetadata.class);
    List<Hit<ProductDocument>> hits =
        documents.stream()
            .map(
                document -> {
                  Hit<ProductDocument> hit = mock(Hit.class);
                  when(hit.source()).thenReturn(document);
                  return hit;
                })
            .toList();
    when(response.hits()).thenReturn(hitsMetadata);
    when(hitsMetadata.hits()).thenReturn(hits);
    when(elasticsearchClient.search(any(SearchRequest.class), eq(ProductDocument.class)))
        .thenReturn(response);
  }

  private ProductDocument document(String id, float[] embedding) {
    return new ProductDocument(
        id,
        "name-" + id,
        "description-" + id,
        null,
        null,
        null,
        null,
        null,
        "image-" + id,
        embedding,
        null,
        null,
        null);
  }

  private float[] embedding(float first, float second) {
    float[] embedding = new float[1536];
    Arrays.fill(embedding, 0.0F);
    embedding[0] = first;
    embedding[1] = second;
    return embedding;
  }

  private SearchHits<ProductDocument> searchHits(ProductDocument document) {
    SearchHit<ProductDocument> hit = mock(SearchHit.class);
    when(hit.getContent()).thenReturn(document);

    SearchHits<ProductDocument> hits = mock(SearchHits.class);
    when(hits.stream()).thenAnswer(ignored -> Stream.of(hit));
    return hits;
  }
}
