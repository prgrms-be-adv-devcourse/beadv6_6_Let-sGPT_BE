package com.openat.member.application.dto;

import com.openat.member.domain.model.WishlistItem;
import java.util.UUID;

/**
 * 찜 항목 응답. {@code WishlistItem}의 내부 PK({@code id})와 {@code createdAt}은 클라이언트가
 * 쓸 곳이 없어 노출하지 않는다 — 목록 정렬(최신순)은 이미 서버가 강제하므로 배열 순서 자체가
 * 그 정보를 담고, 개별 식별/삭제는 전부 {@code productId} 기준이다.
 *
 * <p>객체로 감싸는 이유(단일 필드라도 {@code String[]}로 납작하게 만들지 않는 이유): 나중에 필드가
 * 추가되어도(예: 품절 여부) 객체에 필드를 얹는 건 하위호환 변경이지만, 배열 원소 타입을
 * string → object로 바꾸는 건 breaking change이기 때문.
 */
public record WishlistItemResponse(
        UUID productId
) {
    public static WishlistItemResponse from(WishlistItem wishlistItem) {
        return new WishlistItemResponse(wishlistItem.getProductId());
    }
}
