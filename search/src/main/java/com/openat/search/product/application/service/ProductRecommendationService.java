package com.openat.search.product.application.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.presentation.dto.ProductRecommendationRequest;
import com.openat.search.product.presentation.dto.ProductRecommendationResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRecommendationService {

  private static final int MAX_INPUT_PRODUCTS = 100;
  private static final int DEFAULT_RECOMMENDATION_SIZE = 20;
  private static final int MAX_RECOMMENDATION_SIZE = 100;
  private static final int MIN_KNN_CANDIDATES = 100;
  private static final int KNN_CANDIDATE_MULTIPLIER = 5;
  private static final int EMBEDDING_DIMENSIONS = 1536;
  private static final String PRODUCTS_INDEX = "products";
  private static final String[] RECOMMENDATION_SOURCE_INCLUDES = {
    "id", "name", "description", "imgDescription"
  };

  private final ElasticsearchOperations elasticsearchOperations;
  private final ElasticsearchClient elasticsearchClient;

  public List<ProductRecommendationResponse> recommend(ProductRecommendationRequest request) {
    int recommendationSize = normalizeSize(request.size());
    RecommendationSeeds seeds = parse(request);
    Map<String, ProductDocument> productsById = loadProducts(seeds.ids());
    float[] combinedVector = combineVectors(seeds, productsById);

    List<Query> filters = new ArrayList<>();
    filters.add(activeProductFilter());
    if (!seeds.purchasedIds().isEmpty()) {
      filters.add(excludeIdsFilter(seeds.purchasedIds()));
    }

    NativeQuery query =
        NativeQuery.builder()
            .withKnnSearches(
                knn ->
                    knn.field("embedding")
                        .queryVector(toFloatList(combinedVector))
                        .k(recommendationSize)
                        .numCandidates(
                            Math.max(
                                MIN_KNN_CANDIDATES, recommendationSize * KNN_CANDIDATE_MULTIPLIER))
                        .rescoreVector(rescore -> rescore.oversample(3.0f))
                        .filter(filters))
            .withPageable(PageRequest.of(0, recommendationSize))
            .withSourceFilter(
                FetchSourceFilter.of(
                    builder -> builder.withIncludes(RECOMMENDATION_SOURCE_INCLUDES)))
            .build();

    return elasticsearchOperations.search(query, ProductDocument.class).stream()
        .map(hit -> ProductRecommendationResponse.from(hit.getContent()))
        .toList();
  }

  private RecommendationSeeds parse(ProductRecommendationRequest request) {
    List<String> ids = split(request.id());
    List<String> scores = split(request.score());
    List<String> buyFlags = split(request.buy());

    if (ids.size() != scores.size() || ids.size() != buyFlags.size()) {
      throw invalidInput("id, score, buy must contain the same number of values.");
    }
    if (ids.size() > MAX_INPUT_PRODUCTS) {
      throw invalidInput("At most 100 product ids can be requested.");
    }

    List<Float> weights = new ArrayList<>(ids.size());
    Set<String> purchasedIds = new HashSet<>();
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      try {
        UUID.fromString(id);
      } catch (IllegalArgumentException exception) {
        throw invalidInput("Invalid product id: " + id);
      }

      float weight;
      try {
        weight = Float.parseFloat(scores.get(i));
      } catch (NumberFormatException exception) {
        throw invalidInput("Invalid score: " + scores.get(i));
      }
      if (!Float.isFinite(weight) || weight < 0.0F) {
        throw invalidInput("score must be a finite number greater than or equal to 0.");
      }
      weights.add(weight);

      String buyFlag = buyFlags.get(i).toUpperCase(Locale.ROOT);
      if (!buyFlag.equals("T") && !buyFlag.equals("F")) {
        throw invalidInput("buy must contain only T or F.");
      }
      if (buyFlag.equals("T")) {
        purchasedIds.add(id);
      }
    }

    if (weights.stream().allMatch(weight -> weight == 0.0F)) {
      throw invalidInput("At least one score must be greater than 0.");
    }
    return new RecommendationSeeds(ids, weights, purchasedIds);
  }

  private List<String> split(String value) {
    List<String> values = Arrays.stream(value.split("\\|", -1)).map(String::trim).toList();
    if (values.isEmpty() || values.stream().anyMatch(String::isEmpty)) {
      throw invalidInput("Pipe-separated values must not contain an empty value.");
    }
    return values;
  }

  private Map<String, ProductDocument> loadProducts(List<String> ids) {
    List<String> distinctIds = ids.stream().distinct().toList();
    SearchRequest seedSearchRequest =
        SearchRequest.of(
            search ->
                search
                    .index(PRODUCTS_INDEX)
                    .size(distinctIds.size())
                    .query(query -> query.ids(idQuery -> idQuery.values(distinctIds)))
                    .source(
                        source ->
                            source.filter(
                                filter ->
                                    filter
                                        .includes(
                                            "id",
                                            "name",
                                            "description",
                                            "imgDescription",
                                            "embedding")
                                        .excludeVectors(false))));

    SearchResponse<ProductDocument> seedSearchResponse;
    try {
      seedSearchResponse = elasticsearchClient.search(seedSearchRequest, ProductDocument.class);
    } catch (IOException | ElasticsearchException exception) {
      throw new BusinessException(
          CommonErrorCode.BAD_GATEWAY,
          "Failed to load product embeddings from Elasticsearch.",
          exception);
    }

    Map<String, ProductDocument> productsById = new HashMap<>();
    seedSearchResponse
        .hits()
        .hits()
        .forEach(
            hit -> {
              ProductDocument document = hit.source();
              if (document != null) {
                productsById.put(document.id(), document);
              }
            });

    List<String> missingIds =
        ids.stream().filter(id -> !productsById.containsKey(id)).distinct().toList();
    if (!missingIds.isEmpty()) {
      throw new BusinessException(
          CommonErrorCode.NOT_FOUND,
          "Products not found in Elasticsearch: " + String.join(", ", missingIds));
    }
    return productsById;
  }

  private float[] combineVectors(
      RecommendationSeeds seeds, Map<String, ProductDocument> productsById) {
    float[] combined = null;

    for (int i = 0; i < seeds.ids().size(); i++) {
      String id = seeds.ids().get(i);
      float[] embedding = productsById.get(id).embedding();
      if (embedding == null || embedding.length == 0) {
        throw invalidInput("Product embedding is empty: " + id);
      }
      if (embedding.length != EMBEDDING_DIMENSIONS) {
        throw invalidInput(
            "Product embedding dimensions must be " + EMBEDDING_DIMENSIONS + ": " + id);
      }
      if (combined == null) {
        combined = new float[embedding.length];
      } else if (combined.length != embedding.length) {
        throw invalidInput("Product embedding dimensions do not match.");
      }

      float weight = seeds.weights().get(i);
      float[] weightedVector = new float[embedding.length];
      for (int dimension = 0; dimension < embedding.length; dimension++) {
        weightedVector[dimension] = embedding[dimension] * weight;
        combined[dimension] += weightedVector[dimension];
        if (!Float.isFinite(combined[dimension])) {
          throw invalidInput("The combined product embedding contains a non-finite value.");
        }
      }
      if (log.isInfoEnabled()) {
        log.info(
            "[product-recommendation] {} -> dense_vector * [{}] = {}",
            id,
            weight,
            Arrays.toString(weightedVector));
      }
    }

    if (combined == null || isZeroVector(combined)) {
      throw invalidInput("The combined product embedding must not be a zero vector.");
    }
    if (log.isInfoEnabled()) {
      log.info("[product-recommendation] = dense_vector 합산 {}", Arrays.toString(combined));
    }
    return combined;
  }

  private boolean isZeroVector(float[] vector) {
    for (float value : vector) {
      if (value != 0.0F) {
        return false;
      }
    }
    return true;
  }

  private Query activeProductFilter() {
    return Query.of(
        query ->
            query.bool(
                bool ->
                    bool.mustNot(
                        Query.of(mustNot -> mustNot.exists(exists -> exists.field("deletedAt"))))));
  }

  private Query excludeIdsFilter(Set<String> ids) {
    return Query.of(
        query ->
            query.bool(
                bool ->
                    bool.mustNot(
                        Query.of(
                            mustNot ->
                                mustNot.ids(idQuery -> idQuery.values(new ArrayList<>(ids)))))));
  }

  private List<Float> toFloatList(float[] embedding) {
    List<Float> vector = new ArrayList<>(embedding.length);
    for (float value : embedding) {
      vector.add(value);
    }
    return vector;
  }

  private BusinessException invalidInput(String message) {
    return new BusinessException(CommonErrorCode.INVALID_INPUT, message);
  }

  private int normalizeSize(Integer size) {
    if (size == null) {
      return DEFAULT_RECOMMENDATION_SIZE;
    }
    if (size < 1 || size > MAX_RECOMMENDATION_SIZE) {
      throw invalidInput("size must be between 1 and 100.");
    }
    return size;
  }

  private record RecommendationSeeds(
      List<String> ids, List<Float> weights, Set<String> purchasedIds) {}
}
