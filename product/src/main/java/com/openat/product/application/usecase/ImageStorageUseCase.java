package com.openat.product.application.usecase;

import com.openat.product.application.dto.ImagePresignInfo;

public interface ImageStorageUseCase {
  ImagePresignInfo presignUpload(String contentType);

  String promote(String key);

  byte[] load(String key);
}
