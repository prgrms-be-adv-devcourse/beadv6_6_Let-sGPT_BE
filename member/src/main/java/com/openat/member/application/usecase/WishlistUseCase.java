package com.openat.member.application.usecase;

import com.openat.member.application.dto.WishlistItemResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WishlistUseCase {

    /**
     * 찜 추가. 이미 찜한 상품이면 아무 일도 하지 않는다(멱등).
     *
     * @return 이번 호출로 실제 새로 추가됐으면 true, 이미 찜한 상품이라 no-op이었으면 false.
     *         컨트롤러는 항상 200으로 응답하므로 이 반환값을 쓰지 않지만, 실제 상태 변화 여부를
     *         드러내는 정보라 테스트·향후 호출자를 위해 남겨둔다.
     */
    boolean add(UUID memberId, UUID productId);

    /** 찜 해제. 찜하지 않은 상품이면 아무 일도 하지 않는다(멱등). */
    void remove(UUID memberId, UUID productId);

    /** 본인 찜 목록 조회 — 최신순(createdAt desc) 페이지네이션. */
    Page<WishlistItemResponse> getMyWishlist(UUID memberId, Pageable pageable);
}
