package com.openat.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.Test;

class TokenExchangeServiceTest {

    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final SellerInfoRepository sellerInfoRepository = mock(SellerInfoRepository.class);
    private final TokenExchangeService service =
            new TokenExchangeService(jwtTokenProvider, sellerInfoRepository);

    private final UUID memberId = UUID.randomUUID();
    private final UUID sellerInfoId = UUID.randomUUID();

    @BeforeEach
    void setUpSellerOwnership() {
        given(sellerInfoRepository.findActiveByIdAndMemberId(sellerInfoId, memberId))
                .willReturn(Optional.of(mock(SellerInfo.class)));
        given(jwtTokenProvider.getScopedTokenExpireSeconds()).willReturn(120L);
    }

    @Test
    void omittedTarget_issuesBackwardCompatibleProductToken() {
        given(jwtTokenProvider.createDelegationToken(
                sellerInfoId,
                memberId,
                TokenExchangeRequest.AUDIENCE_PRODUCT,
                TokenExchangeRequest.SCOPE_PRODUCT_WRITE
        )).willReturn("product-token");

        SellerTokenResponse response = service.issueSellerToken(
                memberId,
                sellerInfoId,
                null,
                null
        );

        assertThat(response.accessToken()).isEqualTo("product-token");
    }

    @Test
    void settlementAudienceWithoutScope_issuesSettlementReadToken() {
        given(jwtTokenProvider.createDelegationToken(
                sellerInfoId,
                memberId,
                TokenExchangeRequest.AUDIENCE_SETTLEMENT,
                TokenExchangeRequest.SCOPE_SETTLEMENT_READ
        )).willReturn("settlement-token");

        SellerTokenResponse response = service.issueSellerToken(
                memberId,
                sellerInfoId,
                TokenExchangeRequest.AUDIENCE_SETTLEMENT,
                null
        );

        assertThat(response.accessToken()).isEqualTo("settlement-token");
        verify(jwtTokenProvider).createDelegationToken(
                sellerInfoId,
                memberId,
                TokenExchangeRequest.AUDIENCE_SETTLEMENT,
                TokenExchangeRequest.SCOPE_SETTLEMENT_READ
        );
    }

    @Test
    void mismatchedScope_isRejected() {
        assertThatThrownBy(() -> service.issueSellerToken(
                memberId,
                sellerInfoId,
                TokenExchangeRequest.AUDIENCE_SETTLEMENT,
                TokenExchangeRequest.SCOPE_PRODUCT_WRITE
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_SCOPE));

        verify(jwtTokenProvider, never()).createDelegationToken(any(), any(), any(), any());
    }
}
