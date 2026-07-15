package com.openat.member.application.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 찜 상태 설정(PUT) 요청. {@code wished=true}면 추가, {@code false}면 해제 — 방향을 URL/HTTP
 * 메서드가 아니라 바디로 전달해, 프론트엔드가 현재 렌더링된 상태값을 그대로 흘려보내기만 하면
 * 되도록 한다(호출부에서 add/remove 중 어떤 API 함수를 부를지 분기할 필요가 없음).
 *
 * <p>{@code Boolean}(boxed)을 쓰는 이유: {@code @NotNull}이 필드 누락을 잡아내려면 boxed 타입이어야
 * 한다 — primitive {@code boolean}은 필드가 아예 없어도 기본값 false로 채워져 검증을 통과해버린다.
 */
public record SetWishlistRequest(
        @NotNull Boolean wished
) {
}
