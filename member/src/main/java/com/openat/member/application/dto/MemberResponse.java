package com.openat.member.application.dto;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.model.Role;
import java.util.UUID;

public record MemberResponse(
        UUID id,
        String email,
        String nickname,
        Role role,
        PlatformType platformType
) {
    /** role_history에서 조회한 현재 역할을 함께 받아 생성한다. */
    public static MemberResponse from(Member member, Role currentRole) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                currentRole,
                member.getPlatformType()
        );
    }
}
