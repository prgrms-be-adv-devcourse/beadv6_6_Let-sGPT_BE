package com.openat.member.domain.repository;

import com.openat.member.domain.model.SellerInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerInfoRepository {

    SellerInfo save(SellerInfo sellerInfo);

    List<SellerInfo> findActiveByMemberId(UUID memberId);

    List<SellerInfo> findAllByMemberId(UUID memberId);

    /**
     * sellerId와 memberId가 모두 일치하는 건 조회.
     * 존재하지 않거나 해당 회원 소유가 아니면 empty.
     */
    Optional<SellerInfo> findByIdAndMemberId(UUID sellerId, UUID memberId);
}
