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
    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole(),
                member.getPlatformType()
        );
    }
}
