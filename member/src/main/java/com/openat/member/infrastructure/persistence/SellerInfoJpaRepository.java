package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.SellerInfo;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerInfoJpaRepository extends JpaRepository<SellerInfo, UUID> {

    // 활성(논리적 삭제되지 않은) 행은 회원당 0~1개여야 하지만, 방어적으로 최신 1건만 가져온다.
    Optional<SellerInfo> findFirstByMember_IdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID memberId);

    boolean existsByMember_IdAndDeletedAtIsNull(UUID memberId);
}
