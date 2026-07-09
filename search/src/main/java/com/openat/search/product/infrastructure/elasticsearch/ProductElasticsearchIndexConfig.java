package com.openat.search.product.infrastructure.elasticsearch;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductElasticsearchIndexConfig implements ApplicationRunner {

  private static final String PRODUCTS_MAPPING =
      """
      {
        "properties": {
          "id": {
            "type": "keyword"
          },
          "sellerId": {
            "type": "keyword"
          },
          "name": {
            "type": "text"
          },
          "description": {
            "type": "text"
          },
          "categoryId": {
            "type": "keyword"
          },
          "categoryName": {
            "type": "text"
          },
          "price": {
            "type": "long"
          },
          "thumbnailKey": {
            "type": "keyword"
          },
          "imageKeys": {
            "type": "keyword"
          },
          "embedding": {
            "type": "dense_vector",
            "dims": 1536,
            "index": true,
            "similarity": "cosine",
            "index_options": {
              "type": "bbq_hnsw",
              "m": 16,
              "ef_construction": 100,
              "rescore_vector": {
                "oversample": 3
              }
            }
          },
          "createdAt": {
            "type": "date",
            "format": "date_time"
          },
          "updatedAt": {
            "type": "date",
            "format": "date_time"
          }
        }
      }
      """;

  private static final String EMBEDDING_MAPPING =
      """
      {
        "properties": {
          "embedding": {
            "type": "dense_vector",
            "dims": 1536,
            "index": true,
            "similarity": "cosine",
            "index_options": {
              "type": "bbq_hnsw",
              "m": 16,
              "ef_construction": 100,
              "rescore_vector": {
                "oversample": 3
              }
            }
          }
        }
      }
      """;

  private final ElasticsearchOperations elasticsearchOperations;

  @Override
  public void run(ApplicationArguments args) {
    IndexOperations indexOperations = elasticsearchOperations.indexOps(ProductDocument.class);

    if (!indexOperations.exists()) {
      indexOperations.create(Map.of(), Document.parse(PRODUCTS_MAPPING));
      log.info("[product-index] Created products index with optimized dense_vector mapping");
      return;
    }

    if (hasOptimizedEmbeddingMapping(indexOperations.getMapping())) {
      log.info(
          "[product-index] products index already has optimized embedding dense_vector mapping");
      return;
    }

    indexOperations.putMapping(Document.parse(EMBEDDING_MAPPING));
    log.info("[product-index] Added optimized embedding dense_vector mapping to products index");
  }

  private boolean hasOptimizedEmbeddingMapping(Map<String, Object> mapping) {
    Object properties = mapping.get("properties");
    if (!(properties instanceof Map<?, ?> propertiesMap)) {
      return false;
    }

    Object embedding = propertiesMap.get("embedding");
    if (!(embedding instanceof Map<?, ?> embeddingMap)) {
      return false;
    }

    Object indexOptions = embeddingMap.get("index_options");
    if (!(indexOptions instanceof Map<?, ?> indexOptionsMap)) {
      return false;
    }

    Object rescoreVector = indexOptionsMap.get("rescore_vector");
    if (!(rescoreVector instanceof Map<?, ?> rescoreVectorMap)) {
      return false;
    }

    return "dense_vector".equals(embeddingMap.get("type"))
        && numberEquals(embeddingMap.get("dims"), 1536)
        && Boolean.TRUE.equals(embeddingMap.get("index"))
        && "cosine".equals(embeddingMap.get("similarity"))
        && "bbq_hnsw".equals(indexOptionsMap.get("type"))
        && numberEquals(indexOptionsMap.get("m"), 16)
        && numberEquals(indexOptionsMap.get("ef_construction"), 100)
        && numberEquals(rescoreVectorMap.get("oversample"), 3);
  }

  private boolean numberEquals(Object value, int expected) {
    return value instanceof Number number && number.doubleValue() == expected;
  }
}
