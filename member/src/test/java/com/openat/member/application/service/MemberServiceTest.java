package com.openat.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.LoginRequest;
import com.openat.member.application.dto.TokenResponse;
import com.openat.member.domain.exception.MemberErrorCode;
import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.model.Role;
import com.openat.member.domain.model.RoleHistory;
import com.openat.member.domain.repository.MemberRepository;
import com.openat.member.domain.repository.RefreshTokenRepository;
import com.openat.member.domain.repository.RoleEntityRepository;
import com.openat.member.domain.repository.RoleHistoryRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로그인 시 비밀번호 검증이 탈퇴 여부 판별보다 먼저 일어나는지(계정 열거 취약점 회귀 방지)와
 * 탈퇴 유예기간 복구(restore) 흐름을 검증한다.
 */
class MemberServiceTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RoleEntityRepository roleEntityRepository = mock(RoleEntityRepository.class);
    private final RoleHistoryRepository roleHistoryRepository = mock(RoleHistoryRepository.class);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository, refreshTokenRepository,
                roleEntityRepository, roleHistoryRepository, jwtTokenProvider, passwordEncoder);
    }

    @Test
    @DisplayName("탈퇴한 계정이어도 비밀번호가 틀리면 항상 동일한 INVALID_CREDENTIALS를 던진다(계정 열거 방지)")
    void login_whenPasswordWrong_neverRevealsWithdrawnStatus() {
        Member withdrawn = withdrawnMember();
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(withdrawn));
        when(passwordEncoder.matches("wrong", withdrawn.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> memberService.login(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("비밀번호가 맞고 탈퇴 상태면 그제서야 MEMBER_WITHDRAWN을 던진다")
    void login_whenPasswordCorrectAndWithdrawn_throwsWithdrawn() {
        Member withdrawn = withdrawnMember();
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(withdrawn));
        when(passwordEncoder.matches("correct", withdrawn.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> memberService.login(new LoginRequest("a@b.com", "correct")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_WITHDRAWN);
    }

    @Test
    @DisplayName("활성 계정 + 올바른 비밀번호면 정상적으로 토큰을 발급한다")
    void login_whenActiveAndPasswordCorrect_issuesTokens() {
        Member active = activeMember();
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("correct", active.getPassword())).thenReturn(true);
        stubTokenIssuance(active);

        TokenResponse response = memberService.login(new LoginRequest("a@b.com", "correct"));

        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("복구: 탈퇴 유예기간 중 본인 확인에 성공하면 원자적 UPDATE로 복구하고 토큰을 발급한다")
    void restore_whenDeletedAndCredentialsValid_restoresAndIssuesTokens() {
        Member withdrawn = withdrawnMember();
        Member restored = activeMemberWithSameId(withdrawn);
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(withdrawn));
        when(passwordEncoder.matches("correct", withdrawn.getPassword())).thenReturn(true);
        // restore()는 조회한 엔티티를 직접 mutate하지 않고 조건부 UPDATE 건수로 판단한다 —
        // 1건 갱신됐다고 응답하면 최신 상태를 다시 조회해 issueTokens에 쓴다.
        when(memberRepository.restoreIfWithinGracePeriod(eq(withdrawn.getId()), any())).thenReturn(1);
        when(memberRepository.findByIdIncludingDeleted(withdrawn.getId())).thenReturn(Optional.of(restored));
        stubTokenIssuance(restored);

        TokenResponse response = memberService.restore(new LoginRequest("a@b.com", "correct"));

        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("복구: 조건부 UPDATE가 0건이면(그 사이 익명화되는 등) MEMBER_WITHDRAWN을 던진다")
    void restore_whenConditionalUpdateAffectsNoRows_throwsWithdrawn() {
        Member withdrawn = withdrawnMember();
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(withdrawn));
        when(passwordEncoder.matches("correct", withdrawn.getPassword())).thenReturn(true);
        when(memberRepository.restoreIfWithinGracePeriod(eq(withdrawn.getId()), any())).thenReturn(0);

        assertThatThrownBy(() -> memberService.restore(new LoginRequest("a@b.com", "correct")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_WITHDRAWN);
    }

    @Test
    @DisplayName("복구: 비밀번호가 틀리면 탈퇴 상태와 무관하게 INVALID_CREDENTIALS")
    void restore_whenPasswordWrong_throwsInvalidCredentials() {
        Member withdrawn = withdrawnMember();
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(withdrawn));
        when(passwordEncoder.matches("wrong", withdrawn.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> memberService.restore(new LoginRequest("a@b.com", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_INVALID_CREDENTIALS);
        assertThat(withdrawn.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("복구: 이미 활성 계정이면(중복 호출) 그냥 로그인처럼 멱등하게 처리한다")
    void restore_whenAlreadyActive_behavesLikeLogin() {
        Member active = activeMember();
        when(memberRepository.findByEmailIncludingDeleted("a@b.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("correct", active.getPassword())).thenReturn(true);
        stubTokenIssuance(active);

        TokenResponse response = memberService.restore(new LoginRequest("a@b.com", "correct"));

        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    private void stubTokenIssuance(Member member) {
        RoleHistory roleHistory = mock(RoleHistory.class);
        when(roleHistory.getRole()).thenReturn(Role.ROLE_USER);
        when(roleHistoryRepository.findCurrentByMemberId(member.getId())).thenReturn(Optional.of(roleHistory));
        when(jwtTokenProvider.createAccessToken(any(Member.class), any(Role.class))).thenReturn("access-token");
        when(jwtTokenProvider.createRefreshToken(any(UUID.class), any(String.class))).thenReturn("refresh-token");
        when(jwtTokenProvider.getRefreshTokenExpireSeconds()).thenReturn(1_209_600L);
        when(jwtTokenProvider.getAccessTokenExpireSeconds()).thenReturn(1_800L);
    }

    private Member activeMember() {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email("a@b.com")
                .password("encoded")
                .nickname("nick")
                .build();
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        return member;
    }

    private Member withdrawnMember() {
        Member member = activeMember();
        member.withdraw();
        return member;
    }

    /** restore()가 조건부 UPDATE 이후 다시 조회하는 "복구 완료 상태"를 흉내낸다(같은 id, deletedAt 없음). */
    private Member activeMemberWithSameId(Member withdrawn) {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email(withdrawn.getEmail())
                .password(withdrawn.getPassword())
                .nickname(withdrawn.getNickname())
                .build();
        ReflectionTestUtils.setField(member, "id", withdrawn.getId());
        return member;
    }
}
