package com.openat.search.product.application.vector;

import java.util.Optional;

public interface ProductEmbeddingGenerator {

  Optional<float[]> generate(String text);
}
