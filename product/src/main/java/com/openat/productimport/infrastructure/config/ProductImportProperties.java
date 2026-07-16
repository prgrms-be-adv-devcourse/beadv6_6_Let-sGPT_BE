package com.openat.productimport.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("product.batch-import")
public record ProductImportProperties(
    int maxRows,
    long maxManifestBytes,
    long maxImageBytes,
    int workerThreads,
    List<String> localAllowedRoots,
    List<String> s3AllowedBuckets,
    String s3Region) {

  public ProductImportProperties {
    maxRows = maxRows > 0 ? maxRows : 1000;
    maxManifestBytes = maxManifestBytes > 0 ? maxManifestBytes : 5L * 1024 * 1024;
    maxImageBytes = maxImageBytes > 0 ? maxImageBytes : 10L * 1024 * 1024;
    workerThreads = workerThreads > 0 ? workerThreads : 2;
    localAllowedRoots = clean(localAllowedRoots);
    s3AllowedBuckets = clean(s3AllowedBuckets);
    s3Region = s3Region == null || s3Region.isBlank() ? "ap-northeast-2" : s3Region.trim();
  }

  private static List<String> clean(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .toList();
  }
}
