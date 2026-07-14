package com.openat.member.application.service;

import com.openat.member.application.dto.WishlistItemResponse;
import com.openat.member.application.usecase.WishlistUseCase;
import com.openat.member.domain.model.WishlistItem;
import com.openat.member.domain.repository.WishlistRepository;
import com.openat.member.infrastructure.kafka.event.WishlistChangedEvent;
import com.openat.member.infrastructure.kafka.event.WishlistChangedEvent.ChangeType;
import com.openat.member.infrastructure.outbox.OutboxEventWriter;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 찜(위시리스트) 도메인 서비스.
 *
 * <p>add/remove 모두 멱등하게 구현하고, 실제로 상태가 바뀐 경우에만(행이 insert/delete된 경우에만)
 * 아웃박스 이벤트를 적재한다 — 이미 찜한 상품을 또 add하거나 없는 항목을 remove해도 order 쪽에
 * 유령 토글 이벤트가 전달되지 않도록 하기 위함.
 *
 * <p>아웃박스 aggregateId는 WishlistItem.id가 아니라 memberId(userId)를 사용한다. 물리 삭제 후
 * 재추가는 매번 새 WishlistItem을 만들기 때문에, WishlistItem.id를 key로 쓰면 같은 회원의
 * "삭제→생성" 이벤트가 서로 다른 Kafka 파티션에 흩어져 순서가 깨질 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistService implements WishlistUseCase {

    private static final String AGGREGATE_TYPE = "WISHLIST";

    private final WishlistRepository wishlistRepository;
    private final OutboxEventWriter outboxEventWriter;

    @Value("${member.kafka.topic.wishlist-changed}")
    private String wishlistChangedTopic;

    @Override
    @Transactional
    public boolean add(UUID memberId, UUID productId) {
        if (wishlistRepository.existsByMemberIdAndProductId(memberId, productId)) {
            return false; // 이미 찜한 상품 — no-op (이벤트 미발행)
        }

        wishlistRepository.save(WishlistItem.builder()
                .memberId(memberId)
                .productId(productId)
                .build());

        publish(memberId, productId, ChangeType.CREATE);
        return true;
    }

    @Override
    @Transactional
    public void remove(UUID memberId, UUID productId) {
        long deleted = wishlistRepository.deleteByMemberIdAndProductId(memberId, productId);
        if (deleted == 0) {
            return; // 원래 없던 항목 — no-op (이벤트 미발행)
        }

        publish(memberId, productId, ChangeType.DELETE);
    }

    @Override
    public Page<WishlistItemResponse> getMyWishlist(UUID memberId, Pageable pageable) {
        return wishlistRepository.findPageByMemberId(memberId, pageable)
                .map(WishlistItemResponse::from);
    }

    private void publish(UUID memberId, UUID productId, ChangeType type) {
        WishlistChangedEvent event = new WishlistChangedEvent(memberId, productId, type, Instant.now());
        outboxEventWriter.write(AGGREGATE_TYPE, memberId, wishlistChangedTopic, event);
    }
}
