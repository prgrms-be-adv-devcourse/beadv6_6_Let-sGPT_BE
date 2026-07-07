package com.openat.drop.presentation.dto;

import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropSearchCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record DropSearchRequest(
    @Schema(description = "상태 필터(REGISTERED·OPEN·CLOSE), null: 전체") DropStatus status,
    @Schema(description = "카테고리 id, null: 전체") UUID categoryId,
    @Schema(description = "상품명 검색어, null: 전체") String keyword) {

  public DropSearchCondition toCondition() {
    return new DropSearchCondition(status, categoryId, keyword, null);
  }

  public DropSearchCondition toCondition(UUID sellerId) {
    return new DropSearchCondition(status, categoryId, keyword, sellerId);
  }
}
