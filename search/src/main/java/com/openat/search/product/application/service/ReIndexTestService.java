package com.openat.search.product.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.product.application.dto.ProductSearchSyncTestResponse;
import com.openat.search.product.application.dto.ProductSearchSyncTestResponse.ProductSyncOperation;
import com.openat.search.product.application.dto.ReIndexTestResult;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import com.openat.search.product.infrastructure.persistence.SearchIndexRefreshStateRepository;
import com.openat.search.product.presentation.dto.AiImageAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReIndexTestService {

  private static final String PRODUCTS_STATE_KEY = "products";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final ProductEmbeddingService productEmbeddingService;
  private final ProductSearchDocumentRepository productSearchDocumentRepository;
  private final SearchIndexRefreshStateRepository searchIndexRefreshStateRepository;
  private final AiImageService aiImageService;

  @Value("${search.refresh_index_term:5}")
  private long refreshIndexTerm;

  @Value("${search.refresh_index_enabled:false}")
  private boolean refreshIndexEnabled;

  @Value("${search.product-sync-test-url:http://localhost:9240/api/v1/searchs/search-sync-test}")
  private String productSyncTestUrl;

  @Scheduled(fixedRateString = "${search.refresh_index_term:5}", timeUnit = TimeUnit.MINUTES)
  public void scheduledReIndexTest() {
    if (!refreshIndexEnabled) {
      log.debug("[re_index_test] scheduled execution skipped because refresh index is disabled.");
      return;
    }

    try {
      reIndexTest();
    } catch (RuntimeException e) {
      log.error("[reIndexTest] scheduled execution failed.", e);
    }
  }

  public synchronized ReIndexTestResult reIndexTest() {
    Instant changedAfter =
        searchIndexRefreshStateRepository
            .findLastIndexedAt(PRODUCTS_STATE_KEY)
            .orElse(Instant.EPOCH);

    List<ProductSearchSyncTestResponse> products = fetchProducts(changedAfter);
    log.info(
        "[re_index_test] product sync test response received. changedAfter={}, count={}, json={}",
        changedAfter,
        products.size(),
        toJson(products));

    int indexedCount = 0;
    for (ProductSearchSyncTestResponse product : products) {
      ProductDocument documentToSave = applyEmbeddingOrOriginal(product.toDocument());
      productSearchDocumentRepository.save(documentToSave);
      indexedCount++;
      log.info(
          "Search product re_index_test indexed to Elasticsearch. eventType={}, productId={}, hasEmbedding={}",
          product.operation(),
          documentToSave.id(),
          documentToSave.embedding() != null && documentToSave.embedding().length > 0);
    }

    Instant lastIndexedAt =
        products.stream()
            .map(ProductSearchSyncTestResponse::latestEventAt)
            .max(Comparator.naturalOrder())
            .orElse(changedAfter);

    boolean stateUpdated = false;
    if (indexedCount > 0) {
      searchIndexRefreshStateRepository.saveLastIndexedAt(PRODUCTS_STATE_KEY, lastIndexedAt);
      stateUpdated = true;
    }

    log.info(
        "[re_index_test] completed. refresh_index_term={}min, changedAfter={}, receivedCount={}, insertCount={}, updateCount={}, deleteCount={}, indexedCount={}, lastIndexedAt={}, stateUpdated={}",
        refreshIndexTerm,
        changedAfter,
        products.size(),
        countOperation(products, ProductSyncOperation.INSERT),
        countOperation(products, ProductSyncOperation.UPDATE),
        countOperation(products, ProductSyncOperation.DELETE),
        indexedCount,
        lastIndexedAt,
        stateUpdated);

    return new ReIndexTestResult(
        changedAfter,
        products.size(),
        countOperation(products, ProductSyncOperation.INSERT),
        countOperation(products, ProductSyncOperation.UPDATE),
        countOperation(products, ProductSyncOperation.DELETE),
        indexedCount,
        lastIndexedAt,
        stateUpdated);
  }

  private int countOperation(
      List<ProductSearchSyncTestResponse> products, ProductSyncOperation operation) {
    return (int) products.stream().filter(product -> product.operation() == operation).count();
  }

  private List<ProductSearchSyncTestResponse> fetchProducts(Instant changedAfter) {
    URI uri =
        UriComponentsBuilder.fromUriString(productSyncTestUrl)
            .queryParam("changedAfter", changedAfter.toString())
            .build()
            .toUri();

    ProductSearchSyncTestResponse[] products =
        restClient.get().uri(uri).retrieve().body(ProductSearchSyncTestResponse[].class);

    if (products == null) {
      return List.of();
    }
    return Arrays.asList(products);
  }

  private ProductDocument applyEmbeddingOrOriginal(ProductDocument productDocument) {
    try {

      AiImageAnalyzeResponse aiImageAnalyzeResponse =
              aiImageService.analyzeImageUrl(productDocument.thumbnailKey(), "");
      ProductDocument analyzedProductDocument =
              productDocument.withImgDescription(aiImageAnalyzeResponse.answer());

      return productEmbeddingService.applyEmbedding(analyzedProductDocument);
    } catch (RuntimeException e) {
      log.warn(
          "Search product re_index_test embedding failed. eventType={}, productId={}, name={}, description={}",
          "re_index_test",
          productDocument.id(),
          productDocument.name(),
          productDocument.description(),
          e);
      return productDocument;
    }
  }

  private String toJson(List<ProductSearchSyncTestResponse> products) {
    try {
      return objectMapper.writeValueAsString(products);
    } catch (JsonProcessingException e) {
      return "[]";
    }
  }
}
