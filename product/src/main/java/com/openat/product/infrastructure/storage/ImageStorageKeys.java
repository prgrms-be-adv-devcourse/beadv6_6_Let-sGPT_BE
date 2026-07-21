package com.openat.product.infrastructure.storage;

import java.util.UUID;
import java.util.regex.Pattern;

final class ImageStorageKeys {

  private static final String STAGING_PREFIX = "staging/";
  private static final Pattern FINAL_KEY_PATTERN =
      Pattern.compile(
          "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,10}$");

  private ImageStorageKeys() {}

  static String newStagingKey(String extension) {
    return STAGING_PREFIX + UUID.randomUUID() + "." + extension;
  }

  static boolean isStagingKey(String key) {
    if (key == null || !key.startsWith(STAGING_PREFIX)) {
      return false;
    }
    String finalKey = stripStagingMarker(key);
    return FINAL_KEY_PATTERN.matcher(finalKey).matches();
  }

  static boolean isFinalKey(String key) {
    return key != null && FINAL_KEY_PATTERN.matcher(key).matches();
  }

  static String toFinalKey(String stagingKey) {
    return stripStagingMarker(stagingKey);
  }

  static String toStagingObjectKey(String stagingKey, String stagingPrefix) {
    return stagingPrefix + stripStagingMarker(stagingKey);
  }

  static String toFinalObjectKey(String finalKey, String finalPrefix) {
    return finalPrefix + finalKey;
  }

  private static String stripStagingMarker(String stagingKey) {
    return stagingKey.substring(STAGING_PREFIX.length());
  }
}
