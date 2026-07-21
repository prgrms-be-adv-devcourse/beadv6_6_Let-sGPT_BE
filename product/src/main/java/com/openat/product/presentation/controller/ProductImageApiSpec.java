package com.openat.product.presentation.controller;

import com.openat.product.presentation.dto.ImagePresignRequest;
import com.openat.product.presentation.dto.ImagePresignResponse;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
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

  @Operation(summary = "상품 이미지 조회", description = "키로 저장된 이미지 바이트를 반환한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<byte[]> getImage(String key);
}
