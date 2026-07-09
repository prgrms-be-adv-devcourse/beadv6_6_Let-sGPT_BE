package com.openat.search.product.application.dto;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;

public record ProductSearchResult(ProductDocument document, Float score) {}
