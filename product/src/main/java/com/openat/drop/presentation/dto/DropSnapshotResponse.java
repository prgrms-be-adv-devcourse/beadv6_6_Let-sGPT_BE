package com.openat.drop.presentation.dto;

import com.openat.drop.application.dto.DropSnapshotInfo;
import java.util.UUID;

public record DropSnapshotResponse(
    UUID productId, String productName, UUID sellerId, long unitPrice) {

  public static DropSnapshotResponse from(DropSnapshotInfo info) {
    return new DropSnapshotResponse(
        info.productId(), info.productName(), info.sellerId(), info.unitPrice());
  }
}
