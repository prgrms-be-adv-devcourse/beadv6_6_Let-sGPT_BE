package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.WishlistItem;
import com.openat.member.domain.repository.WishlistRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WishlistRepositoryAdaptor implements WishlistRepository {

    private final WishlistJpaRepository wishlistJpaRepository;

    @Override
    public WishlistItem save(WishlistItem wishlistItem) {
        return wishlistJpaRepository.save(wishlistItem);
    }

    @Override
    public boolean existsByMemberIdAndProductId(UUID memberId, UUID productId) {
        return wishlistJpaRepository.existsByMemberIdAndProductId(memberId, productId);
    }

    @Override
    public long deleteByMemberIdAndProductId(UUID memberId, UUID productId) {
        return wishlistJpaRepository.deleteByMemberIdAndProductId(memberId, productId);
    }

    @Override
    public Page<WishlistItem> findPageByMemberId(UUID memberId, Pageable pageable) {
        return wishlistJpaRepository.findByMemberId(memberId, pageable);
    }
}
