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

    TokenResponse refresh(RefreshRequest request);

    MemberResponse getMyInfo(UUID memberId);

    void logout(UUID memberId);

    MemberResponse updateMember(UUID memberId, UpdateMemberRequest request);

    void withdraw(UUID memberId);
}
