package com.openat.member.application.usecase;

import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import java.util.List;
import java.util.UUID;

public interface SellerUseCase {

    /** 본인 판매자 정보 목록 조회. isActive=false면 전체, true면 활성만. */
    List<SellerInfoResponse> getMySellerInfo(UUID memberId, boolean isActive);

    /** 관리자 전용: userId(memberId)로 해당 회원의 판매자 정보 전체 조회. */
    List<SellerInfoResponse> getSellerInfosByUserId(UUID userId);

    SellerInfoResponse create(UUID memberId, CreateSellerInfoRequest request);

    SellerInfoResponse patch(UUID memberId, UUID sellerId, PatchSellerInfoRequest request);

    void delete(UUID memberId, UUID sellerId);
}
