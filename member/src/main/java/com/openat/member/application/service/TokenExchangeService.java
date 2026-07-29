package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.SellerTokenResponse;
import com.openat.member.application.dto.TokenExchangeRequest;
import com.openat.member.application.usecase.TokenExchangeUseCase;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.repository.SellerInfoRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenExchangeService implements TokenExchangeUseCase {

    private final JwtTokenProvider jwtTokenProvider;
    private final SellerInfoRepository sellerInfoRepository;

    @Override
    public SellerTokenResponse issueSellerToken(
            UUID memberId,
            UUID sellerInfoId,
            String requestedAudience,
            String requestedScope
    ) {
        // 소유권 + 활성 검증 (soft-delete 제외)
        sellerInfoRepository.findActiveByIdAndMemberId(sellerInfoId, memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER));

        String audience = requestedAudience == null
                ? TokenExchangeRequest.AUDIENCE_PRODUCT
                : requestedAudience;
        String scope = requestedScope == null
                ? defaultScopeFor(audience)
                : requestedScope;
        validateTarget(audience, scope);

        // scoped 토큰 발급 (delegation: sub=sellerInfoId, act.sub=memberId)
        String scopedToken = jwtTokenProvider.createDelegationToken(
                sellerInfoId, memberId, audience, scope);

        return SellerTokenResponse.of(scopedToken, jwtTokenProvider.getScopedTokenExpireSeconds());
    }

    private String defaultScopeFor(String audience) {
        return switch (audience) {
            case TokenExchangeRequest.AUDIENCE_PRODUCT -> TokenExchangeRequest.SCOPE_PRODUCT_WRITE;
            case TokenExchangeRequest.AUDIENCE_SETTLEMENT -> TokenExchangeRequest.SCOPE_SETTLEMENT_READ;
            default -> throw new BusinessException(
                    SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE);
        };
    }

    private void validateTarget(String audience, String scope) {
        String expectedScope = defaultScopeFor(audience);

        if (!expectedScope.equals(scope)) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_SCOPE);
        }
    }
}
