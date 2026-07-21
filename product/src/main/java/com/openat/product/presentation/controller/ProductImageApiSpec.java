package com.openat.product.presentation.controller;

import com.openat.product.presentation.dto.ImagePresignRequest;
import com.openat.product.presentation.dto.ImagePresignResponse;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "ProductImage", description = "상품 이미지 API")
public interface ProductImageApiSpec {

  @Operation(
      summary = "상품 이미지 업로드 URL 발급",
      description =
          "검증된 콘텐츠 타입에 맞는 확장자로 staging 키와 presigned PUT URL을 발급한다. 응답의 stagingKey를 상품 thumbnailKey·imageKeys로 사용한다.")
  @ApiResponse(responseCode = "200", description = "presigned URL 발급 성공")
  @ApiErrorResponses
  ResponseEntity<ImagePresignResponse> presign(ImagePresignRequest request);

  @Operation(
      summary = "상품 이미지 조회",
      description = "키에 해당하는 presigned GET URL로 리다이렉트한다. 이미지 바이트는 스토리지가 직접 응답한다.")
  @ApiResponse(
      responseCode = "302",
      description = "presigned GET URL로 리다이렉트",
      headers = @Header(name = "Location", description = "이미지 presigned GET URL"))
  @ApiErrorResponses
  ResponseEntity<Void> getImage(String key);
}
