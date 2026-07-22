package com.openat.search.product.application.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.openat.search.product.application.dto.ProductSearchResult;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;
  private static final int KNN_CANDIDATE_MULTIPLIER = 10;
  private static final int MIN_RERANK_CANDIDATES = 500;
  private static final int MAX_KNN_CANDIDATES = 10_000;
  private static final float KNN_RESCORE_OVERSAMPLE = 5.0F;
  private static final float LEXICAL_SCORE_BOOST_WEIGHT = 0.30F;
  private static final String[] VECTOR_SEARCH_SOURCE_INCLUDES = {
    "id",
    "sellerId",
    "name",
    "description",
    "categoryId",
    "categoryName",
    "sellerName",
    "price",
    "thumbnailKey",
    "imageKeys",
    "imgDescription",
    "embedding",
    "createdAt",
    "updatedAt",
    "deletedAt"
  };

  private final ElasticsearchOperations elasticsearchOperations;
  private final ProductEmbeddingService productEmbeddingService;
  private final MeterRegistry meterRegistry;

  public Page<ProductSearchResult> search(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      Integer page,
      Integer size) {
    return search(queryText, categoryName, startPrice, endPrice, page, size, null);
  }

  public Page<ProductSearchResult> search(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      Integer page,
      Integer size,
      String sort) {
    String type = StringUtils.hasText(queryText) ? "vector" : "keyword";
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      return doSearch(queryText, categoryName, startPrice, endPrice, page, size, sort);
    } finally {
      sample.stop(meterRegistry.timer("search.query", "type", type));
    }
  }

  private Page<ProductSearchResult> doSearch(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      Integer page,
      Integer size,
      String sort) {
    int normalizedPage = normalizePage(page);
    int normalizedSize = normalizeSize(size);
    ProductSort requestedSort = ProductSort.from(sort);

    if (StringUtils.hasText(queryText)) {
      return vectorSearch(
          queryText,
          categoryName,
          startPrice,
          endPrice,
          PageRequest.of(normalizedPage, normalizedSize),
          requestedSort);
    }

    ProductSort productSort = requestedSort == null ? ProductSort.CREATED_AT_DESC : requestedSort;
    PageRequest pageable =
        PageRequest.of(normalizedPage, normalizedSize, productSort.toSpringSort());

    NativeQuery query =
        NativeQuery.builder()
            .withQuery(
                queryBuilder ->
                    queryBuilder.bool(
                        bool ->
                            bool.filter(buildSearchFilters(categoryName, startPrice, endPrice))))
            .withPageable(pageable)
            .build();

    var searchHits = elasticsearchOperations.search(query, ProductDocument.class);
    var content = searchHits.stream().map(this::toResult).toList();
    return new PageImpl<>(content, pageable, searchHits.getTotalHits());
  }

  private Page<ProductSearchResult> vectorSearch(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      PageRequest pageable,
      ProductSort requestedSort) {
    float[] queryVector =
        productEmbeddingService
            .embed(queryText.trim())
            .filter(embedding -> embedding.length > 0)
            .orElseThrow(
                () -> new IllegalStateException("Product search query embedding is empty."));

    int requestedWindow = Math.addExact((int) pageable.getOffset(), pageable.getPageSize());
    int candidateWindow =
        Math.min(MAX_KNN_CANDIDATES, Math.max(MIN_RERANK_CANDIDATES, requestedWindow));
    int numCandidates =
        Math.min(
            MAX_KNN_CANDIDATES,
            Math.max(candidateWindow, candidateWindow * KNN_CANDIDATE_MULTIPLIER));
    PageRequest candidatePageable = PageRequest.of(0, candidateWindow);

    NativeQuery query =
        NativeQuery.builder()
            .withKnnSearches(
                knn ->
                    knn.field("embedding")
                        .queryVector(toFloatList(queryVector))
                        .k(candidateWindow)
                        .numCandidates(numCandidates)
                        .rescoreVector(rescore -> rescore.oversample(KNN_RESCORE_OVERSAMPLE))
                        .filter(buildSearchFilters(categoryName, startPrice, endPrice)))
            .withPageable(candidatePageable)
            .withSourceFilter(
                FetchSourceFilter.of(
                    builder -> builder.withIncludes(VECTOR_SEARCH_SOURCE_INCLUDES)))
            .build();

    var searchHits = elasticsearchOperations.search(query, ProductDocument.class);
    Comparator<ProductSearchResult> resultComparator =
        requestedSort == null
            ? Comparator.comparing(
                ProductSearchResult::score, Comparator.nullsLast(Comparator.reverseOrder()))
            : requestedSort.resultComparator();

    var rankedResults =
        searchHits.stream()
            .map(searchHit -> toHybridResult(searchHit, queryVector, queryText))
            .filter(result -> matchesSearchTermsInProductContext(queryText, result.document()))
            .sorted(resultComparator)
            .toList();

    return new PageImpl<>(pageContent(rankedResults, pageable), pageable, rankedResults.size());
  }

  private List<Query> buildSearchFilters(String categoryName, Long startPrice, Long endPrice) {
    List<Query> filters = new ArrayList<>();

    filters.add(
        Query.of(
            query ->
                query.bool(
                    bool ->
                        bool.mustNot(
                            Query.of(
                                mustNot -> mustNot.exists(exists -> exists.field("deletedAt")))))));

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
    float hybridScore = scoreOrZero(vectorScore) + (lexicalScore * LEXICAL_SCORE_BOOST_WEIGHT);
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
    String imgDescription = normalizeText(document.imgDescription());

    float score = 0.0F;
    if (!normalizedQuery.isBlank() && containsSearchExpression(name, normalizedQuery)) {
      score += 0.4F;
    }
    if (!normalizedQuery.isBlank() && containsSearchExpression(description, normalizedQuery)) {
      score += 0.2F;
    }
    if (!normalizedQuery.isBlank() && containsSearchExpression(imgDescription, normalizedQuery)) {
      score += 0.25F;
    }

    for (String term : terms) {
      if (containsSearchTerm(name, term)) {
        score += 0.35F;
      }
      if (containsSearchTerm(description, term)) {
        score += 0.12F;
      }
      if (containsSearchTerm(imgDescription, term)) {
        score += 0.18F;
      }
    }

    return Math.min(1.0F, score);
  }

  private boolean matchesSearchTermsInProductContext(String queryText, ProductDocument document) {
    Set<String> terms = searchTerms(queryText);
    if (terms.isEmpty()) {
      return lexicalScore(queryText, document) > 0.0F;
    }

    if (attributeContexts(document).stream()
        .anyMatch(context -> terms.stream().allMatch(term -> containsSearchTerm(context, term)))) {
      return true;
    }

    return hasPrimaryStandaloneAttributeMatch(terms, document);
  }

  private boolean hasPrimaryStandaloneAttributeMatch(Set<String> terms, ProductDocument document) {
    List<String> imageAttributes = splitAttributeContexts(document.imgDescription(), "[,;\\r\\n]+");
    boolean queryMatchesPrimaryStandaloneAttribute =
        imageAttributes.stream()
            .filter(this::isStandalonePrimaryAttribute)
            .anyMatch(
                attribute -> terms.stream().anyMatch(term -> containsSearchTerm(attribute, term)));

    if (!queryMatchesPrimaryStandaloneAttribute) {
      return false;
    }

    String primaryProductDescription =
        String.join(
            " ",
            normalizeText(document.name()),
            normalizeText(document.categoryName()),
            normalizeText(document.imgDescription()));
    return terms.stream().allMatch(term -> containsSearchTerm(primaryProductDescription, term));
  }

  private boolean containsSearchExpression(String text, String expression) {
    return isHangulTerm(expression) ? containsSearchTerm(text, expression) : text.contains(expression);
  }

  private boolean containsSearchTerm(String text, String term) {
    if (!isHangulTerm(term)) {
      return text.contains(term);
    }

    int fromIndex = 0;
    while (fromIndex < text.length()) {
      int matchIndex = text.indexOf(term, fromIndex);
      if (matchIndex < 0) {
        return false;
      }

      int matchEnd = matchIndex + term.length();
      boolean startsAtWordBoundary =
          matchIndex == 0 || !Character.isLetterOrDigit(text.codePointBefore(matchIndex));
      boolean endsAtWordBoundary =
          matchEnd == text.length() || !Character.isLetterOrDigit(text.codePointAt(matchEnd));
      if (startsAtWordBoundary
          && (endsAtWordBoundary || hasStandaloneColorSuffix(text, matchEnd))) {
        return true;
      }
      fromIndex = matchEnd;
    }
    return false;
  }

  private boolean hasStandaloneColorSuffix(String text, int matchEnd) {
    int suffixEnd = matchEnd + 1;
    return suffixEnd <= text.length()
        && text.startsWith("색", matchEnd)
        && (suffixEnd == text.length()
            || !Character.isLetterOrDigit(text.codePointAt(suffixEnd)));
  }

  private boolean isHangulTerm(String value) {
    return !value.isBlank()
        && value.codePoints().allMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
  }

  private List<String> attributeContexts(ProductDocument document) {
    List<String> contexts = new ArrayList<>();
    String name = normalizeText(document.name());
    List<String> imageAttributes = splitAttributeContexts(document.imgDescription(), "[,;\\r\\n]+");

    if (!name.isBlank()) {
      contexts.add(name);
    }
    if (!imageAttributes.isEmpty()) {
      List<String> primaryProductAttributes = new ArrayList<>();
      if (!name.isBlank()) {
        primaryProductAttributes.add(name);
      }
      primaryProductAttributes.add(imageAttributes.getFirst());
      imageAttributes.stream()
          .skip(1)
          .filter(this::isStandalonePrimaryAttribute)
          .forEach(primaryProductAttributes::add);
      contexts.add(String.join(" ", primaryProductAttributes));
      contexts.addAll(imageAttributes);
    }

    contexts.addAll(splitAttributeContexts(document.description(), "[.!?;\\r\\n]+"));
    return contexts;
  }

  private boolean isStandalonePrimaryAttribute(String attribute) {
    return !attribute.isBlank() && attribute.indexOf(' ') < 0;
  }

  private List<String> splitAttributeContexts(String value, String delimiterRegex) {
    String normalizedValue = normalizeText(value);
    if (normalizedValue.isBlank()) {
      return List.of();
    }

    return List.of(normalizedValue.split(delimiterRegex)).stream()
        .map(String::trim)
        .filter(context -> !context.isBlank())
        .toList();
  }

  private Set<String> searchTerms(String queryText) {
    Set<String> terms = new LinkedHashSet<>();
    String normalizedQuery = normalizeText(queryText);
    if (normalizedQuery.isBlank()) {
      return terms;
    }

    for (String token : normalizedQuery.split("\\s+")) {
      if (isMeaningfulSearchTerm(token)) {
        terms.add(token);
      }
    }

    return terms;
  }

  private boolean isMeaningfulSearchTerm(String token) {
    if (token.length() >= 2) {
      return true;
    }
    return token.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
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

  private enum ProductSort {
    CREATED_AT_DESC("createdAt", Sort.Direction.DESC),
    PRICE_ASC("price", Sort.Direction.ASC),
    PRICE_DESC("price", Sort.Direction.DESC);

    private final String field;
    private final Sort.Direction direction;

    ProductSort(String field, Sort.Direction direction) {
      this.field = field;
      this.direction = direction;
    }

    private static ProductSort from(String value) {
      if (!StringUtils.hasText(value)) {
        return null;
      }

      return switch (value) {
        case "createdAt,desc" -> CREATED_AT_DESC;
        case "price,asc" -> PRICE_ASC;
        case "price,desc" -> PRICE_DESC;
        default ->
            throw new IllegalArgumentException(
                "sort must be one of createdAt,desc, price,asc, price,desc");
      };
    }

    private Sort toSpringSort() {
      return Sort.by(direction, field);
    }

    private Comparator<ProductSearchResult> resultComparator() {
      return switch (this) {
        case CREATED_AT_DESC ->
            Comparator.comparing(
                result -> result.document().createdAt(),
                Comparator.nullsLast(Comparator.reverseOrder()));
        case PRICE_ASC ->
            Comparator.comparing(
                result -> result.document().price(),
                Comparator.nullsLast(Comparator.naturalOrder()));
        case PRICE_DESC ->
            Comparator.comparing(
                result -> result.document().price(),
                Comparator.nullsLast(Comparator.reverseOrder()));
      };
    }
  }
}
