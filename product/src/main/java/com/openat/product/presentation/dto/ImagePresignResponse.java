package com.openat.product.presentation.dto;

import com.openat.product.application.dto.ImagePresignInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ImagePresignResponse(
    @Schema(description = "상품 등록·수정에 사용할 staging 이미지 키") String stagingKey,
    @Schema(description = "이미지 업로드용 presigned PUT URL") String uploadUrl,
    @Schema(description = "presigned URL 만료 시각") Instant expiresAt) {

  public static ImagePresignResponse from(ImagePresignInfo upload) {
    return new ImagePresignResponse(upload.stagingKey(), upload.uploadUrl(), upload.expiresAt());
  }
}
