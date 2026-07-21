package com.openat.product.presentation.controller;

import com.openat.product.application.dto.ImagePresignInfo;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.presentation.dto.ImagePresignRequest;
import com.openat.product.presentation.dto.ImagePresignResponse;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products/images")
@RequiredArgsConstructor
public class ProductImageController implements ProductImageApiSpec {

  private final ImageStorageUseCase imageStorageUseCase;

  @Override
  @PostMapping("/presign")
  public ResponseEntity<ImagePresignResponse> presign(
      @Valid @RequestBody ImagePresignRequest request) {
    ImagePresignInfo upload = imageStorageUseCase.presignUpload(request.contentType());
    return ResponseEntity.ok(ImagePresignResponse.from(upload));
  }

  @Override
  @GetMapping("/{key}")
  public ResponseEntity<Void> getImage(@PathVariable String key) {
    URI downloadUrl = imageStorageUseCase.presignDownload(key);
    return ResponseEntity.status(HttpStatus.FOUND).location(downloadUrl).build();
  }
}
