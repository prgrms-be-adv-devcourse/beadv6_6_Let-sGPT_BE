package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.response.ApiResponse;
import com.openat.member.application.dto.*;
import com.openat.member.application.usecase.MemberUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * gateway에서 "/member" 접두사가 StripPrefix로 제거된 뒤 그대로 매칭되는 경로.
 * 회원가입(POST)은 apigateway SecurityConfig에서 permitAll, "/me"(내정보)는 별도 규칙이 없어
 * anyExchange().authenticated()에 걸려 JWT가 있어야만 접근 가능하다.
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberUseCase memberUseCase;

    @PostMapping
    public ApiResponse<MemberResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        return ApiResponse.of(memberUseCase.signUp(request), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(memberUseCase.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(memberUseCase.refresh(request));
    }

    @GetMapping("/me")
    public ApiResponse<MemberResponse> getMyInfo(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ApiResponse.ok(memberUseCase.getMyInfo(memberId));
    }

    /**
     * permitAll 목록에 없으므로 apigateway의 anyExchange().authenticated()에 걸려
     * 유효한 access token이 있어야만 호출 가능. 즉 로그아웃 자체도 본인 확인을 거친다.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        memberUseCase.logout(memberId);
        return ApiResponse.ok(null);
    }

    /** password/nickname만 수정 가능. 인증 필요(permitAll 아님). */
    @PatchMapping("/me")
    public ApiResponse<MemberResponse> updateMember(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ApiResponse.ok(memberUseCase.updateMember(memberId, request));
    }

    /** 논리적 삭제(탈퇴). 인증 필요(permitAll 아님). */
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        memberUseCase.withdraw(memberId);
        return ApiResponse.ok(null);
    }
}
