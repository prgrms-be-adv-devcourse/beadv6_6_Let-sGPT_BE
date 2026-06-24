package com.openat.member.application.usecase;

import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import java.util.List;
import java.util.UUID;

public interface SellerUseCase {

    /** 본인 판매자 정보 목록 조회. isActive=false면 전체, true면 활성만. */
    List<SellerInfoResponse> getMySellerInfo(UUID memberId, boolean isActive);

    /** 관리자 전용: 판매자 정보 UUID로 단건 조회. */
    SellerInfoResponse getSellerInfoById(UUID sellerId);

    SellerInfoResponse create(UUID memberId, CreateSellerInfoRequest request);

    SellerInfoResponse patch(UUID memberId, UUID sellerId, PatchSellerInfoRequest request);

    void delete(UUID memberId, UUID sellerId);
}
