package com.openat.product.presentation.controller;

import com.openat.product.presentation.dto.ImageUploadResponse;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "ProductImage", description = "상품 이미지 API")
public interface ProductImageApiSpec {

  @Operation(
      summary = "상품 이미지 업로드",
      description =
          "이미지 파일을 업로드한다. 응답 본문의 key 를 상품 thumbnailKey·imageKeys 로 사용하고, url 로 미리보기를 띄운다.")
  @ApiResponse(
      responseCode = "201",
      description = "업로드 성공 — 본문으로 { key, url } 반환",
      headers = @Header(name = "Location", description = "이미지 조회 URI"))
  @ApiErrorResponses
  ResponseEntity<ImageUploadResponse> upload(MultipartFile file);

  @Operation(summary = "상품 이미지 조회", description = "키로 저장된 이미지 바이트를 반환한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<byte[]> getImage(String key);
}
