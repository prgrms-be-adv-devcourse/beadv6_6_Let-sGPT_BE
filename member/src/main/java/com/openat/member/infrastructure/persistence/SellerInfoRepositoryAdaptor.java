package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.SellerInfoRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link SellerInfoRepository} 포트를 {@link SellerInfoJpaRepository}(Spring Data JPA)로 구현하는 어댑터.
 */
@Repository
@RequiredArgsConstructor
public class SellerInfoRepositoryAdaptor implements SellerInfoRepository {

    private final SellerInfoJpaRepository sellerInfoJpaRepository;

    @Override
    public SellerInfo save(SellerInfo sellerInfo) {
        return sellerInfoJpaRepository.save(sellerInfo);
    }

    @Override
    public Optional<SellerInfo> findActiveByMemberId(UUID memberId) {
        return sellerInfoJpaRepository.findFirstByMember_IdAndDeletedAtIsNullOrderByCreatedAtDesc(memberId);
    }

    @Override
    public boolean existsActiveByMemberId(UUID memberId) {
        return sellerInfoJpaRepository.existsByMember_IdAndDeletedAtIsNull(memberId);
    }
}
