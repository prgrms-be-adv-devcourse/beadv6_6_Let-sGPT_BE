package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.SellerInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerInfoJpaRepository extends JpaRepository<SellerInfo, UUID> {

    List<SellerInfo> findByMember_IdAndDeletedAtIsNull(UUID memberId);

    List<SellerInfo> findByMember_IdOrderByCreatedAtDesc(UUID memberId);

    Optional<SellerInfo> findByIdAndMember_Id(UUID id, UUID memberId);

    /** STS 소유권 검증 전용: soft-delete된 판매자 제외 (F4 버그 수정) */
    Optional<SellerInfo> findByIdAndMember_IdAndDeletedAtIsNull(UUID id, UUID memberId);
}
