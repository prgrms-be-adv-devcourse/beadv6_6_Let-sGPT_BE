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
import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.RefreshTokenRepository;
import com.openat.member.domain.repository.SellerInfoRepository;
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
    private final SellerInfoRepository sellerInfoRepository;
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

    /**
     * 탈퇴(논리적 삭제)한 계정인지 구분해서 알려주기 위해 deletedAt 여부와 무관하게 먼저 찾고,
     * 탈퇴 상태면 {@link MemberErrorCode#WITHDRAWN_MEMBER}를 던진다.
     * (이메일 자체가 존재하지 않는 경우와는 다르게 즉시 구분 — 비밀번호 검증 전에 판별한다.)
     */
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

    /**
     * Redis에 저장된 refresh token(jti)을 삭제해 즉시 무효화한다.
     * access token은 stateless라 만료 전까지는 계속 유효하지만(JWT의 일반적인 한계),
     * 적어도 refresh로 재발급받는 건 더 이상 불가능해진다.
     */
    @Override
    public void logout(UUID memberId) {
        refreshTokenRepository.delete(memberId);
    }

    /**
     * password/nickname만 수정 가능. 둘 다 선택값이라 보낸 필드만 바뀐다.
     * 엔티티가 영속 상태로 조회된 채 트랜잭션 안에서 변경되므로, JPA dirty checking으로
     * 트랜잭션 종료 시 자동 UPDATE된다(별도 save 호출 불필요).
     */
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

    /**
     * 논리적 삭제(deletedAt만 채움) + 더 이상 재로그인하지 못하도록 refresh token도 같이 무효화.
     */
    @Override
    @Transactional
    public void withdraw(UUID memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.withdraw();
        refreshTokenRepository.delete(memberId);
    }

    /**
     * access/refresh 토큰을 새로 발급하고, refresh의 jti를 Redis에 덮어써 이전 refresh 토큰을 무효화한다(rotation).
     */
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

    /** 회원 조회 시에는 항상 활성(논리적 삭제되지 않은) SellerInfo를 같이 조회해서 응답에 포함시킨다. */
    private MemberResponse toResponse(Member member) {
        SellerInfo sellerInfo = sellerInfoRepository.findActiveByMemberId(member.getId()).orElse(null);
        return MemberResponse.from(member, sellerInfo);
    }
}
