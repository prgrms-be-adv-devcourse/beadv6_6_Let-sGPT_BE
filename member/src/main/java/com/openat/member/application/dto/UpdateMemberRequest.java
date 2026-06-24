package com.openat.member.application.dto;

import jakarta.validation.constraints.Size;

/**
 * 회원 정보 수정 — password/nickname만 변경 가능. 둘 다 선택값이라 보낸 필드만 갱신된다
 * (둘 다 비워서 보내면 아무것도 바뀌지 않는다).
 */
public record UpdateMemberRequest(
        @Size(min = 8, max = 64) String password,
        @Size(max = 30) String nickname
) {
}
