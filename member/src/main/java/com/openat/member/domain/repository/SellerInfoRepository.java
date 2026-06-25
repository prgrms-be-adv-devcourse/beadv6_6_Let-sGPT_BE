package com.openat.member.domain.repository;

import com.openat.member.domain.model.SellerInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerInfoRepository {

    SellerInfo save(SellerInfo sellerInfo);

    List<SellerInfo> findActiveByMemberId(UUID memberId);

    List<SellerInfo> findAllByMemberId(UUID memberId);

    Optional<SellerInfo> findByIdAndMemberId(UUID sellerId, UUID memberId);
}
