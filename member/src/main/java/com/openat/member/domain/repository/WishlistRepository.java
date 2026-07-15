package com.openat.member.domain.repository;

import com.openat.member.domain.model.WishlistItem;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WishlistRepository {

    WishlistItem save(WishlistItem wishlistItem);

    boolean existsByMemberIdAndProductId(UUID memberId, UUID productId);

    /** 실제로 삭제된 행 수를 반환한다(0이면 원래 없던 항목 — 호출자가 멱등 판단에 사용). */
    long deleteByMemberIdAndProductId(UUID memberId, UUID productId);

    /** 최신순(createdAt desc) 페이지네이션. */
    Page<WishlistItem> findPageByMemberId(UUID memberId, Pageable pageable);
}
