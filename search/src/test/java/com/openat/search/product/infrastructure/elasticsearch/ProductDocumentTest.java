package com.openat.search.product.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;

class ProductDocumentTest {

  @Test
  void omitsDeletedAtFromElasticsearchSourceWhenValueIsNull() {
    Document source = writeToSource(null);

    assertThat(source).doesNotContainKey("deletedAt");
  }

  @Test
  void writesDeletedAtToElasticsearchSourceWhenValueExists() {
    Instant deletedAt = Instant.parse("2026-07-10T01:00:00Z");
    Document source = writeToSource(deletedAt);

    assertThat(source).containsKey("deletedAt");
    assertThat(Instant.parse((String) source.get("deletedAt"))).isEqualTo(deletedAt);
  }

  private Document writeToSource(Instant deletedAt) {
    SimpleElasticsearchMappingContext mappingContext = new SimpleElasticsearchMappingContext();
    mappingContext.afterPropertiesSet();
    MappingElasticsearchConverter converter = new MappingElasticsearchConverter(mappingContext);
    converter.afterPropertiesSet();

    ProductDocument productDocument =
        new ProductDocument(
            "product-id",
            "상품명",
            "상품 설명",
            "category-id",
            "카테고리",
            "판매자",
            10_000L,
            "thumbnail.jpg",
            "이미지 설명",
            null,
            Instant.parse("2026-07-10T00:00:00Z"),
            Instant.parse("2026-07-10T00:00:00Z"),
            deletedAt);
    Document source = Document.create();

    converter.write(productDocument, source);
    return source;
  }
}
