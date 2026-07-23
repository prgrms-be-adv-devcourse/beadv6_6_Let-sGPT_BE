package com.openat.recommendation.domain.model;

import java.util.UUID;

public record Seed(UUID productId, double score, boolean buy) {

  public Seed {
    if (productId == null) {
      throw new IllegalArgumentException("productId must not be null");
    }
    if (score < 0 || score >= 1) {
      throw new IllegalArgumentException(
          "score must be greater than or equal to 0 and less than 1");
    }
  }
}
