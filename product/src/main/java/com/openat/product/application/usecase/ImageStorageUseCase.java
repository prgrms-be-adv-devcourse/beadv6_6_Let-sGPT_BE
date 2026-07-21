package com.openat.product.application.usecase;

import com.openat.product.application.dto.ImagePresignInfo;
import java.net.URI;

public interface ImageStorageUseCase {
  ImagePresignInfo presignUpload(String contentType);

  String promote(String key);

  URI presignDownload(String key);
}
