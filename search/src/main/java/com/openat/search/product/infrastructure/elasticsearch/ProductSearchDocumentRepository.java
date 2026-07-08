package com.openat.search.product.infrastructure.elasticsearch;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ProductSearchDocumentRepository
    extends ElasticsearchRepository<ProductDocument, String> {

  Page<ProductDocument> findByCategoryNameContaining(String categoryName, Pageable pageable);
}
