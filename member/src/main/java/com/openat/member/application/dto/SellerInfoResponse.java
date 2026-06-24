package com.openat.member.application.dto;

import com.openat.member.domain.model.SellerInfo;
import java.util.UUID;

public record SellerInfoResponse(
        UUID id,
        String businessNumber,
        String storeName,
        boolean active
) {
    public static SellerInfoResponse from(SellerInfo sellerInfo) {
        return new SellerInfoResponse(
                sellerInfo.getId(),
                sellerInfo.getBusinessNumber(),
                sellerInfo.getStoreName(),
                !sellerInfo.isDeleted()
        );
    }
}
