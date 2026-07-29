package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.SellerTokenResponse;
import com.openat.member.application.dto.TokenExchangeRequest;
import com.openat.member.application.usecase.TokenExchangeUseCase;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.repository.SellerInfoRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenExchangeService implements TokenExchangeUseCase {

    /**
     * audience → scope 매핑. 지원하는 audience를 여기서 확장한다.
     * (product 판매자 write, settlement 판매자 정산 조회)
     */
    private static final Map<String, String> SUPPORTED_AUDIENCE_SCOPES = Map.of(
            TokenExchangeRequest.AUDIENCE_PRODUCT, TokenExchangeRequest.SCOPE_PRODUCT_WRITE,
            TokenExchangeRequest.AUDIENCE_SETTLEMENT, TokenExchangeRequest.SCOPE_SETTLEMENT_READ
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final SellerInfoRepository sellerInfoRepository;

    @Override
    public SellerTokenResponse issueSellerToken(UUID memberId, UUID sellerInfoId, String audience) {
        // 소유권 + 활성 검증 (soft-delete 제외)
        sellerInfoRepository.findActiveByIdAndMemberId(sellerInfoId, memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER));

        // audience 미지정 시 하위 호환을 위해 기존 product 기본값을 유지한다.
        String resolvedAudience = StringUtils.hasText(audience)
                ? audience
                : TokenExchangeRequest.AUDIENCE_PRODUCT;
        String scope = SUPPORTED_AUDIENCE_SCOPES.get(resolvedAudience);
        if (scope == null) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE);
        }

        // scoped 토큰 발급 (delegation: sub=sellerInfoId, act.sub=memberId)
        String scopedToken = jwtTokenProvider.createDelegationToken(
                sellerInfoId, memberId, resolvedAudience, scope);

        return SellerTokenResponse.of(scopedToken, jwtTokenProvider.getScopedTokenExpireSeconds());
    }
}
