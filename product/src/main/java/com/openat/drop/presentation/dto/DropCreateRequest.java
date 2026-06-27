package com.openat.drop.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.openat.drop.application.dto.DropCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

public record DropCreateRequest(
    @Schema(description = "드롭할 상품 식별자", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        UUID productId,
    @Schema(description = "판매 확정가(원)", example = "219000") @NotNull @Positive Long dropPrice,
    @Schema(description = "총 수량", example = "100") @Positive int totalQuantity,
    @Schema(description = "1인 구매 한도(미지정 시 무제한)", example = "2") @Positive Integer limitPerUser,
    @Schema(description = "오픈 시각(미래)", example = "2026-07-01T00:00:00Z") @NotNull @Future
        Instant openAt,
    @Schema(description = "종료 시각(미지정 시 무기한)", example = "2026-07-08T00:00:00Z") Instant closeAt) {

  @JsonIgnore
  @AssertTrue(message = "종료 시각은 오픈 시각 이후여야 합니다.")
  public boolean isCloseAtAfterOpenAt() {
    return closeAt == null || openAt == null || closeAt.isAfter(openAt);
  }

  public DropCreateCommand toCommand(UUID sellerId) {
    return new DropCreateCommand(
        sellerId, productId, dropPrice, totalQuantity, limitPerUser, openAt, closeAt);
  }
}
