package com.openat.member.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.common.web.Locations;
import com.openat.member.application.dto.*;
import com.openat.member.application.usecase.MemberUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberUseCase memberUseCase;

    @PostMapping
    public ResponseEntity<MemberResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        MemberResponse response = memberUseCase.signUp(request);
        return ResponseEntity.created(Locations.fromCurrentRequest(response.id())).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(memberUseCase.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(memberUseCase.refresh(request));
    }

    @GetMapping("/me")
    public ResponseEntity<MemberResponse> getMyInfo(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(memberUseCase.getMyInfo(memberId));
    }

    /**
     * permitAll 목록에 없으므로 apigateway의 anyExchange().authenticated()에 걸려
     * 유효한 access token이 있어야만 호출 가능. 즉 로그아웃 자체도 본인 확인을 거친다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        memberUseCase.logout(memberId);
        return ResponseEntity.noContent().build();
    }

    /** password/nickname만 수정 가능. 인증 필요(permitAll 아님). */
    @PatchMapping("/me")
    public ResponseEntity<MemberResponse> updateMember(
            @CurrentUser UserContext userContext,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        UUID memberId = UUID.fromString(userContext.userId());
        return ResponseEntity.ok(memberUseCase.updateMember(memberId, request));
    }

    /** 논리적 삭제(탈퇴). 인증 필요(permitAll 아님). */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        memberUseCase.withdraw(memberId);
        return ResponseEntity.noContent().build();
    }
}
