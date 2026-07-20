package com.openat.product.infrastructure.storage;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class ImageStorageKeys {

  private static final Pattern EXTENSION_PATTERN = Pattern.compile("^[a-z0-9]{1,10}$");

  private ImageStorageKeys() {}

  static String newKey(String originalFilename) {
    String key = UUID.randomUUID().toString();
    if (originalFilename == null) {
      return key;
    }

    int dot = originalFilename.lastIndexOf('.');
    if (dot < 0) {
      return key;
    }

    String extension = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!EXTENSION_PATTERN.matcher(extension).matches()) {
      return key;
    }
    return key + "." + extension;
  }
}
