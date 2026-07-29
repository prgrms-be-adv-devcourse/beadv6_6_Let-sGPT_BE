package com.openat.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.SellerTokenResponse;
import com.openat.member.application.dto.TokenExchangeRequest;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.model.SellerInfo;
import com.openat.member.domain.repository.SellerInfoRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A안(scoped 토큰 audience 확장) 회귀 테스트.
 *
 * <p>{@code TokenExchangeService.issueSellerToken}이 audience에 따라 올바른 scope로
 * 위임 토큰을 발급하는지, 그리고 하위 호환(미지정 시 product 기본값)이 깨지지 않는지 검증한다.
 * 소유권 검증({@code findActiveByIdAndMemberId})은 audience 분기보다 먼저 실행돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
class TokenExchangeServiceTest {

    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID SELLER_INFO_ID = UUID.randomUUID();
    private static final String SCOPED_TOKEN = "scoped-token-value";

    @Mock
    JwtTokenProvider jwtTokenProvider;

    @Mock
    SellerInfoRepository sellerInfoRepository;

    TokenExchangeService tokenExchangeService;

    @BeforeEach
    void setUp() {
        tokenExchangeService = new TokenExchangeService(jwtTokenProvider, sellerInfoRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("audience를 생략하면 하위 호환을 위해 product 기본값으로 발급한다")
    void issueSellerToken_blankAudience_fallsBackToProduct(String audience) {
        given(sellerInfoRepository.findActiveByIdAndMemberId(SELLER_INFO_ID, MEMBER_ID))
                .willReturn(Optional.of(mock(SellerInfo.class)));
        given(jwtTokenProvider.createDelegationToken(
                eq(SELLER_INFO_ID), eq(MEMBER_ID),
                eq(TokenExchangeRequest.AUDIENCE_PRODUCT), eq(TokenExchangeRequest.SCOPE_PRODUCT_WRITE)))
                .willReturn(SCOPED_TOKEN);
        given(jwtTokenProvider.getScopedTokenExpireSeconds()).willReturn(120L);

        SellerTokenResponse response = tokenExchangeService.issueSellerToken(MEMBER_ID, SELLER_INFO_ID, audience);

        assertThat(response.accessToken()).isEqualTo(SCOPED_TOKEN);
        assertThat(response.expiresIn()).isEqualTo(120L);
    }

    @Test
    @DisplayName("audience=openat-settlement이면 settlement:read scope로 발급한다")
    void issueSellerToken_settlementAudience_issuesSettlementScope() {
        given(sellerInfoRepository.findActiveByIdAndMemberId(SELLER_INFO_ID, MEMBER_ID))
                .willReturn(Optional.of(mock(SellerInfo.class)));
        given(jwtTokenProvider.createDelegationToken(
                eq(SELLER_INFO_ID), eq(MEMBER_ID),
                eq(TokenExchangeRequest.AUDIENCE_SETTLEMENT), eq(TokenExchangeRequest.SCOPE_SETTLEMENT_READ)))
                .willReturn(SCOPED_TOKEN);
        given(jwtTokenProvider.getScopedTokenExpireSeconds()).willReturn(120L);

        SellerTokenResponse response = tokenExchangeService.issueSellerToken(
                MEMBER_ID, SELLER_INFO_ID, TokenExchangeRequest.AUDIENCE_SETTLEMENT);

        assertThat(response.accessToken()).isEqualTo(SCOPED_TOKEN);
        verify(jwtTokenProvider).createDelegationToken(
                SELLER_INFO_ID, MEMBER_ID,
                TokenExchangeRequest.AUDIENCE_SETTLEMENT, TokenExchangeRequest.SCOPE_SETTLEMENT_READ);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openat-unknown", "product", "openat-settlement ", "OPENAT-SETTLEMENT"})
    @DisplayName("지원하지 않는 audience는 소유권 검증 통과 후에도 거부된다")
    void issueSellerToken_unsupportedAudience_throws(String audience) {
        given(sellerInfoRepository.findActiveByIdAndMemberId(SELLER_INFO_ID, MEMBER_ID))
                .willReturn(Optional.of(mock(SellerInfo.class)));

        assertThatThrownBy(() -> tokenExchangeService.issueSellerToken(MEMBER_ID, SELLER_INFO_ID, audience))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE);

        verify(jwtTokenProvider, never()).createDelegationToken(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"openat-product", "openat-settlement"})
    @DisplayName("소유권이 없는 스토어는 audience와 무관하게 거부된다 (소유권 검증이 audience 분기보다 먼저)")
    void issueSellerToken_notOwner_rejectsBeforeAudienceCheck(String audience) {
        given(sellerInfoRepository.findActiveByIdAndMemberId(SELLER_INFO_ID, MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> tokenExchangeService.issueSellerToken(MEMBER_ID, SELLER_INFO_ID, audience))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER);

        verify(jwtTokenProvider, never()).createDelegationToken(any(), any(), any(), any());
    }
}
