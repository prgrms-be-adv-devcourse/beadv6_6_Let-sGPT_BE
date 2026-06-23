package com.openat.member.application.dto;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.SellerInfo;
import java.util.UUID;

public record MemberResponse(
        UUID id,
        String email,
        String nickname,
        Role role,
        PlatformType platformType,
        SellerInfoResponse sellerInfo
) {
    /** 활성 SellerInfo가 없는 일반 회원용. */
    public static MemberResponse from(Member member) {
        return from(member, null);
    }

    /**
     * 회원 조회 시에는 항상 활성(논리적 삭제되지 않은) SellerInfo를 같이 조회해서 내려준다.
     * 없으면 {@code sellerInfo}는 null.
     */
    public static MemberResponse from(Member member, SellerInfo sellerInfo) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole(),
                member.getPlatformType(),
                sellerInfo == null ? null : SellerInfoResponse.from(sellerInfo)
        );
    }
}
