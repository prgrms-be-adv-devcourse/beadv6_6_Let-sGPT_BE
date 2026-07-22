package com.openat.drop.presentation.dto;

import com.openat.drop.application.dto.DropInfo;
import com.openat.drop.domain.model.DropStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record DropResponse(
    @Schema(description = "드롭 id") UUID id,
    @Schema(description = "상품 id") UUID productId,
    @Schema(description = "상품명") String productName,
    @Schema(description = "판매자 스토어 표시명, null: 미연동") String sellerName,
    @Schema(description = "카테고리 id, null: 미분류") UUID categoryId,
    @Schema(description = "카테고리명, null: 미분류") String categoryName,
    @Schema(description = "썸네일 키") String thumbnailKey,
    @Schema(description = "판매 확정가") long dropPrice,
    @Schema(description = "총 수량") int totalQuantity,
    @Schema(description = "잔여 수량(재고 게이트키퍼 파생)") int remainingQuantity,
    @Schema(description = "드롭 상태(OPEN·SOLD_OUT은 오픈 시각·잔여로 파생)") DropStatus status,
    @Schema(description = "오픈 시각") Instant openAt,
    @Schema(description = "종료 시각, null: 매진까지") Instant closeAt,
    @Schema(description = "1인당 구매 한도, null: 제한 없음") Integer limitPerUser) {

  public static DropResponse from(DropInfo info) {
    return new DropResponse(
        info.id(),
        info.productId(),
        info.productName(),
        info.sellerName(),
        info.categoryId(),
        info.categoryName(),
        info.thumbnailKey(),
        info.dropPrice(),
        info.totalQuantity(),
        info.remainingQuantity(),
        info.status(),
        info.openAt(),
        info.closeAt(),
        info.limitPerUser());
  }
}
