package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.SellerInfoRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerInfoRepositoryAdaptor implements SellerInfoRepository {

    private final SellerInfoJpaRepository sellerInfoJpaRepository;

    @Override
    public SellerInfo save(SellerInfo sellerInfo) {
        return sellerInfoJpaRepository.save(sellerInfo);
    }

    @Override
    public List<SellerInfo> findActiveByMemberId(UUID memberId) {
        return sellerInfoJpaRepository.findByMember_IdAndDeletedAtIsNull(memberId);
    }

    @Override
    public List<SellerInfo> findAllByMemberId(UUID memberId) {
        return sellerInfoJpaRepository.findByMember_IdOrderByCreatedAtDesc(memberId);
    }

    @Override
    public Optional<SellerInfo> findByIdAndMemberId(UUID sellerId, UUID memberId) {
        return sellerInfoJpaRepository.findByIdAndMember_Id(sellerId, memberId);
    }

    @Override
    public Optional<SellerInfo> findActiveByIdAndMemberId(UUID sellerId, UUID memberId) {
        return sellerInfoJpaRepository.findByIdAndMember_IdAndDeletedAtIsNull(sellerId, memberId);
    }
}
