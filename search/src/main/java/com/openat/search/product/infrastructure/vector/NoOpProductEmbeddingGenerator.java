package com.openat.search.product.infrastructure.vector;

import com.openat.search.product.application.vector.ProductEmbeddingGenerator;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NoOpProductEmbeddingGenerator implements ProductEmbeddingGenerator {

  @Override
  public Optional<float[]> generate(String text) {
    return Optional.empty();
  }
}
