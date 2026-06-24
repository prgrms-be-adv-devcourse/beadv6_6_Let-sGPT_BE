package com.openat.member.application.usecase;

import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import java.util.List;
import java.util.UUID;

public interface SellerUseCase {

    List<SellerInfoResponse> getMySellerInfo(UUID memberId, boolean isActive);

    SellerInfoResponse create(UUID memberId, CreateSellerInfoRequest request);

    SellerInfoResponse patch(UUID memberId, UUID sellerId, PatchSellerInfoRequest request);

    void delete(UUID memberId, UUID sellerId);
}
