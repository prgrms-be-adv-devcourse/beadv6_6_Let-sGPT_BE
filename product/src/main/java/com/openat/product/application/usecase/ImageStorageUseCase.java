package com.openat.product.application.usecase;

public interface ImageStorageUseCase {
  String store(byte[] content, String originalFilename);

  byte[] load(String key);
}
