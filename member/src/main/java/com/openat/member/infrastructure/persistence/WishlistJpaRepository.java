package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.WishlistItem;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistJpaRepository extends JpaRepository<WishlistItem, UUID> {

    boolean existsByMemberIdAndProductId(UUID memberId, UUID productId);

    /** 파생 delete 쿼리 — 실제로 삭제된 행 수를 반환한다(멱등 판단용). */
    long deleteByMemberIdAndProductId(UUID memberId, UUID productId);

    /** 정렬(최신순 내림차순)은 서비스가 전달하는 Pageable에 고정으로 실려 온다. */
    Page<WishlistItem> findByMemberId(UUID memberId, Pageable pageable);
}
