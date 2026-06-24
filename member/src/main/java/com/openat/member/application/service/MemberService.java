package com.openat.member.application.service;

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
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.RefreshTokenRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import com.openat.common.exception.BusinessException;
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
                .role(Role.ROLE_USER)
                .platformType(PlatformType.LOCAL)
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .build();

        return toResponse(memberRepository.save(member));
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
        // 자기 자신의 현재 닉네임으로 "변경"하는 건 중복이 아니므로 실제로 바뀌는 경우만 검사.
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

    private TokenResponse issueTokens(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member);

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
        return MemberResponse.from(member);
    }
}
