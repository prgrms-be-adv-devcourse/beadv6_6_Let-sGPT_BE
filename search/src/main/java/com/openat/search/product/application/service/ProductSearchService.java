package com.openat.search.product.application.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.openat.search.product.application.dto.ProductSearchResult;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;
  private static final int KNN_CANDIDATE_MULTIPLIER = 5;
  private static final int MIN_KNN_CANDIDATES = 100;
  private static final float VECTOR_SCORE_WEIGHT = 0.45F;
  private static final float LEXICAL_SCORE_WEIGHT = 0.55F;
  private static final String[] VECTOR_SEARCH_SOURCE_INCLUDES = {
    "id",
    "sellerId",
    "name",
    "description",
    "categoryId",
    "categoryName",
    "price",
    "thumbnailKey",
    "imageKeys",
    "embedding",
    "createdAt",
    "updatedAt"
  };

  private final ElasticsearchOperations elasticsearchOperations;
  private final ProductEmbeddingService productEmbeddingService;

  @Value("${elastic.search.score:0.0}")
  private float minimumSearchScore;

  public Page<ProductSearchResult> search(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      Integer page,
      Integer size) {
    int normalizedPage = normalizePage(page);
    int normalizedSize = normalizeSize(size);

    if (StringUtils.hasText(queryText)) {
      return vectorSearch(
          queryText,
          categoryName,
          startPrice,
          endPrice,
          PageRequest.of(normalizedPage, normalizedSize));
    }

    PageRequest pageable =
        PageRequest.of(normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"));

    Criteria criteria = new Criteria();

    if (StringUtils.hasText(categoryName)) {
      criteria = criteria.and(new Criteria("categoryName").contains(categoryName.trim()));
    }
    if (startPrice != null) {
      criteria = criteria.and(new Criteria("price").greaterThanEqual(startPrice));
    }
    if (endPrice != null) {
      criteria = criteria.and(new Criteria("price").lessThanEqual(endPrice));
    }

    CriteriaQuery query = new CriteriaQuery(criteria).setPageable(pageable);

    var searchHits = elasticsearchOperations.search(query, ProductDocument.class);
    var content = searchHits.stream().map(this::toResult).toList();
    return new PageImpl<>(content, pageable, searchHits.getTotalHits());
  }

  private Page<ProductSearchResult> vectorSearch(
      String queryText, String categoryName, Long startPrice, Long endPrice, PageRequest pageable) {
    float[] queryVector =
        productEmbeddingService
            .embed(queryText.trim())
            .filter(embedding -> embedding.length > 0)
            .orElseThrow(
                () -> new IllegalStateException("Product search query embedding is empty."));

    int requestedWindow = Math.addExact((int) pageable.getOffset(), pageable.getPageSize());
    int candidateWindow = Math.max(MIN_KNN_CANDIDATES, requestedWindow);
    int numCandidates = Math.max(MIN_KNN_CANDIDATES, candidateWindow * KNN_CANDIDATE_MULTIPLIER);
    PageRequest candidatePageable = PageRequest.of(0, candidateWindow);

    NativeQuery query =
        NativeQuery.builder()
            .withKnnSearches(
                knn ->
                    knn.field("embedding")
                        .queryVector(toFloatList(queryVector))
                        .k(candidateWindow)
                        .numCandidates(numCandidates)
                        .rescoreVector(rescore -> rescore.oversample(3.0f))
                        .filter(buildVectorFilters(categoryName, startPrice, endPrice)))
            .withPageable(candidatePageable)
            .withSourceFilter(
                FetchSourceFilter.of(
                    builder -> builder.withIncludes(VECTOR_SEARCH_SOURCE_INCLUDES)))
            .build();

    var searchHits = elasticsearchOperations.search(query, ProductDocument.class);
    var rankedResults =
        searchHits.stream()
            .map(searchHit -> toHybridResult(searchHit, queryVector, queryText))
            .filter(this::hasEnoughScore)
            .sorted(
                (left, right) ->
                    Float.compare(scoreOrZero(right.score()), scoreOrZero(left.score())))
            .toList();

    return new PageImpl<>(pageContent(rankedResults, pageable), pageable, rankedResults.size());
  }

  private List<Query> buildVectorFilters(String categoryName, Long startPrice, Long endPrice) {
    List<Query> filters = new ArrayList<>();

    if (StringUtils.hasText(categoryName)) {
      filters.add(
          Query.of(
              query ->
                  query.match(match -> match.field("categoryName").query(categoryName.trim()))));
    }

    if (startPrice != null || endPrice != null) {
      filters.add(
          Query.of(
              query ->
                  query.range(
                      range ->
                          range.number(
                              number -> {
                                number.field("price");
                                if (startPrice != null) {
                                  number.gte(startPrice.doubleValue());
                                }
                                if (endPrice != null) {
                                  number.lte(endPrice.doubleValue());
                                }
                                return number;
                              }))));
    }

    return filters;
  }

  private ProductSearchResult toResult(SearchHit<ProductDocument> searchHit) {
    return new ProductSearchResult(searchHit.getContent(), safeScore(searchHit.getScore()));
  }

  private ProductSearchResult toHybridResult(
      SearchHit<ProductDocument> searchHit, float[] queryVector, String queryText) {
    ProductDocument document = searchHit.getContent();
    Float vectorScore = cosineScore(queryVector, document.embedding());
    if (vectorScore == null) {
      vectorScore = safeScore(searchHit.getScore());
    }

    float lexicalScore = lexicalScore(queryText, document);
    float hybridScore =
        (scoreOrZero(vectorScore) * VECTOR_SCORE_WEIGHT) + (lexicalScore * LEXICAL_SCORE_WEIGHT);
    return new ProductSearchResult(document, Math.min(1.0F, hybridScore));
  }

  private List<Float> toFloatList(float[] embedding) {
    List<Float> vector = new ArrayList<>(embedding.length);
    for (float value : embedding) {
      vector.add(value);
    }
    return vector;
  }

  private Float cosineScore(float[] queryVector, float[] productVector) {
    if (queryVector == null
        || productVector == null
        || queryVector.length != productVector.length) {
      return null;
    }

    double dotProduct = 0.0D;
    double queryNorm = 0.0D;
    double productNorm = 0.0D;

    for (int i = 0; i < queryVector.length; i++) {
      dotProduct += queryVector[i] * productVector[i];
      queryNorm += queryVector[i] * queryVector[i];
      productNorm += productVector[i] * productVector[i];
    }

    if (queryNorm == 0.0D || productNorm == 0.0D) {
      return null;
    }

    double cosine = dotProduct / (Math.sqrt(queryNorm) * Math.sqrt(productNorm));
    double normalizedScore = (cosine + 1.0D) / 2.0D;
    return (float) normalizedScore;
  }

  private float lexicalScore(String queryText, ProductDocument document) {
    Set<String> terms = searchTerms(queryText);
    if (terms.isEmpty()) {
      return 0.0F;
    }

    String normalizedQuery = normalizeText(queryText);
    String name = normalizeText(document.name());
    String description = normalizeText(document.description());
    String categoryName = normalizeText(document.categoryName());

    float score = 0.0F;
    if (!normalizedQuery.isBlank() && name.contains(normalizedQuery)) {
      score += 0.4F;
    }
    if (!normalizedQuery.isBlank() && description.contains(normalizedQuery)) {
      score += 0.2F;
    }

    for (String term : terms) {
      if (name.contains(term)) {
        score += 0.35F;
      }
      if (categoryName.contains(term)) {
        score += 0.25F;
      }
      if (description.contains(term)) {
        score += 0.12F;
      }
    }

    return Math.min(1.0F, score);
  }

  private Set<String> searchTerms(String queryText) {
    Set<String> terms = new LinkedHashSet<>();
    String normalizedQuery = normalizeText(queryText);
    if (normalizedQuery.isBlank()) {
      return terms;
    }

    for (String token : normalizedQuery.split("\\s+")) {
      if (token.length() >= 2) {
        terms.add(token);
      }
    }

    if (normalizedQuery.contains("가방") || normalizedQuery.contains("백")) {
      terms.add("가방");
      terms.add("백");
      terms.add("크로스백");
      terms.add("미니백");
      terms.add("토트백");
      terms.add("숄더백");
      terms.add("백팩");
      terms.add("핸드백");
    }

    if (normalizedQuery.contains("가볍")
        || normalizedQuery.contains("들고")
        || normalizedQuery.contains("휴대")) {
      terms.add("가벼운");
      terms.add("미니");
      terms.add("컴팩트");
      terms.add("크로스");
    }

    return terms;
  }

  private String normalizeText(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).trim();
  }

  private List<ProductSearchResult> pageContent(
      List<ProductSearchResult> rankedResults, PageRequest pageable) {
    int fromIndex = Math.min((int) pageable.getOffset(), rankedResults.size());
    int toIndex = Math.min(fromIndex + pageable.getPageSize(), rankedResults.size());
    return rankedResults.subList(fromIndex, toIndex);
  }

  private boolean hasEnoughScore(ProductSearchResult result) {
    return scoreOrZero(result.score()) >= minimumSearchScore;
  }

  private float scoreOrZero(Float score) {
    return score == null || Float.isNaN(score) || Float.isInfinite(score) ? 0.0F : score;
  }

  private Float safeScore(float score) {
    if (Float.isNaN(score) || Float.isInfinite(score)) {
      return null;
    }
    return score;
  }

  private int normalizePage(Integer page) {
    if (page == null || page < 0) {
      return 0;
    }
    return page;
  }

  private int normalizeSize(Integer size) {
    if (size == null || size <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }
}
