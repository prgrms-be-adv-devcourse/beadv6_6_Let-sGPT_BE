package com.openat.product.presentation.controller;

import com.openat.common.exception.BusinessException;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.domain.error.ProductErrorCode;
import com.openat.product.presentation.dto.ImageUploadResponse;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/products/images")
@RequiredArgsConstructor
public class ProductImageController implements ProductImageApiSpec {

  private final ImageStorageUseCase imageStorageUseCase;

  @Override
  @PostMapping
  public ResponseEntity<ImageUploadResponse> upload(@RequestPart("file") MultipartFile file) {
    String key = imageStorageUseCase.store(readBytes(file), file.getOriginalFilename());
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest().path("/{key}").buildAndExpand(key).toUri();
    return ResponseEntity.created(location).body(new ImageUploadResponse(key, location.getPath()));
  }

  @Override
  @GetMapping("/{key}")
  public ResponseEntity<byte[]> getImage(@PathVariable String key) {
    byte[] content = imageStorageUseCase.load(key);
    MediaType contentType =
        MediaTypeFactory.getMediaType(key).orElse(MediaType.APPLICATION_OCTET_STREAM);
    return ResponseEntity.ok().contentType(contentType).body(content);
  }

  private byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new BusinessException(ProductErrorCode.IMAGE_STORAGE_FAILED);
    }
  }
}
