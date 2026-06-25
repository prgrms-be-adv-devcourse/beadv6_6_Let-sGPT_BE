package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.LoginRequest;
import com.openat.member.application.dto.MemberResponse;
import com.openat.member.application.dto.RefreshRequest;
import com.openat.member.application.dto.SignUpRequest;
import com.openat.member.application.dto.TokenResponse;
import com.openat.member.application.dto.UpdateMemberRequest;
import com.openat.member.application.usecase.MemberUseCase;
import com.openat.member.domain.exception.MemberErrorCode;
import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleEntity;
import com.openat.member.domain.model.RoleHistory;
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.RefreshTokenRepository;
import com.openat.member.domain.repository.RoleEntityRepository;
import com.openat.member.domain.repository.RoleHistoryRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService implements MemberUseCase {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RoleEntityRepository roleEntityRepository;
    private final RoleHistoryRepository roleHistoryRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public MemberResponse signUp(SignUpRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(MemberErrorCode.MEMBER_DUPLICATE_EMAIL);
        }
        if (memberRepository.existsByNickname(request.nickname())) {
            throw new BusinessException(MemberErrorCode.MEMBER_DUPLICATE_NICKNAME);
        }

        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .build();
        Member saved = memberRepository.save(member);

        // 회원가입 시 ROLE_USER 이력 생성
        RoleEntity userRole = findRoleEntity(Role.ROLE_USER);
        roleHistoryRepository.save(RoleHistory.of(saved, userRole));

        return MemberResponse.from(saved, Role.ROLE_USER);
    }

    @Override
    public TokenResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmailIncludingDeleted(request.email())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_INVALID_CREDENTIALS));

        if (member.isDeleted()) {
            throw new BusinessException(MemberErrorCode.MEMBER_WITHDRAWN);
        }
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new BusinessException(MemberErrorCode.MEMBER_INVALID_CREDENTIALS);
        }

        return issueTokens(member);
    }

    @Override
    public TokenResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtTokenProvider.parseRefreshToken(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(MemberErrorCode.MEMBER_INVALID_REFRESH_TOKEN);
        }

        UUID memberId = UUID.fromString(claims.getSubject());
        String tokenId = jwtTokenProvider.getTokenId(claims);
        if (!refreshTokenRepository.isValid(memberId, tokenId)) {
            throw new BusinessException(MemberErrorCode.MEMBER_INVALID_REFRESH_TOKEN);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        return issueTokens(member);
    }

    @Override
    public MemberResponse getMyInfo(UUID memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
        return toResponse(member);
    }

    @Override
    public void logout(UUID memberId) {
        refreshTokenRepository.delete(memberId);
    }

    @Override
    @Transactional
    public MemberResponse updateMember(UUID memberId, UpdateMemberRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (request.password() != null) {
            member.changePassword(passwordEncoder.encode(request.password()));
        }
        if (request.nickname() != null && !request.nickname().equals(member.getNickname())) {
            if (memberRepository.existsByNickname(request.nickname())) {
                throw new BusinessException(MemberErrorCode.MEMBER_DUPLICATE_NICKNAME);
            }
            member.changeNickname(request.nickname());
        }

        return toResponse(member);
    }

    @Override
    @Transactional
    public void withdraw(UUID memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.withdraw();
        refreshTokenRepository.delete(memberId);
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    private TokenResponse issueTokens(Member member) {
        Role currentRole = getCurrentRole(member.getId());
        String accessToken = jwtTokenProvider.createAccessToken(member, currentRole);

        String tokenId = UUID.randomUUID().toString();
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getId(), tokenId);
        refreshTokenRepository.save(
                member.getId(),
                tokenId,
                Duration.ofSeconds(jwtTokenProvider.getRefreshTokenExpireSeconds())
        );

        return TokenResponse.of(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpireSeconds());
    }

    private MemberResponse toResponse(Member member) {
        return MemberResponse.from(member, getCurrentRole(member.getId()));
    }

    /**
     * role_history에서 현재 유효한 역할을 조회한다.
     * 데이터 정합성 오류가 아닌 이상 항상 존재해야 하며, 없으면 서버 오류로 처리한다.
     */
    private Role getCurrentRole(UUID memberId) {
        return roleHistoryRepository.findCurrentByMemberId(memberId)
                .map(RoleHistory::getRole)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    private RoleEntity findRoleEntity(Role role) {
        return roleEntityRepository.findByRole(role)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_ROLE_NOT_CONFIGURED));
    }
}
