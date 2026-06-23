package com.openat.member.application.usecase;

import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.PatchSellerInfoRequest;
import com.openat.member.application.dto.SellerInfoResponse;
import com.openat.member.application.dto.UpdateSellerInfoRequest;
import java.util.UUID;

public interface SellerUseCase {

    /** 활성 SellerInfo가 없을 때만 새로 생성. role을 ROLE_SELLER로 올린다. */
    SellerInfoResponse create(UUID memberId, CreateSellerInfoRequest request);

    /**
     * businessNumber/storeName을 동시에 교체. 기존 활성 SellerInfo가 있으면 논리적 삭제 후
     * 요청 값으로 새로 생성(없었으면 그냥 생성). role을 ROLE_SELLER로 올린다.
     */
    SellerInfoResponse update(UUID memberId, UpdateSellerInfoRequest request);

    /** storeName만 수정. 활성 SellerInfo가 있어야 한다. */
    SellerInfoResponse patch(UUID memberId, PatchSellerInfoRequest request);

    /** 활성 SellerInfo를 논리적 삭제. 더 이상 활성 SellerInfo가 없으면 role을 ROLE_USER로 내린다. */
    void delete(UUID memberId);
}
