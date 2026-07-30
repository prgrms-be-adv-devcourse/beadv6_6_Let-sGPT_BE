package com.openat.member.application.usecase;

import com.openat.member.application.dto.LoginRequest;
import com.openat.member.application.dto.MemberResponse;
import com.openat.member.application.dto.RefreshRequest;
import com.openat.member.application.dto.SignUpRequest;
import com.openat.member.application.dto.TokenResponse;
import com.openat.member.application.dto.UpdateMemberRequest;
import java.util.UUID;

public interface MemberUseCase {

    MemberResponse signUp(SignUpRequest request);

    TokenResponse login(LoginRequest request);

    /**
     * 탈퇴 유예기간(30일) 중인 계정을 본인 확인(email+password) 후 복구하고 즉시 로그인시킨다.
     * 이미 탈퇴 상태가 아니면(중복 호출 등) 그냥 정상 로그인과 동일하게 동작한다(멱등).
     */
    TokenResponse restore(LoginRequest request);

    TokenResponse refresh(RefreshRequest request);

    MemberResponse getMyInfo(UUID memberId);

    void logout(UUID memberId);

    MemberResponse updateMember(UUID memberId, UpdateMemberRequest request);

    void withdraw(UUID memberId);
}
