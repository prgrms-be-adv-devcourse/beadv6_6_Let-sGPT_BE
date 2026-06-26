package com.openat.member.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.member.application.dto.TokenExchangeRequest;
import com.openat.member.application.dto.TokenExchangeResponse;
import com.openat.member.application.usecase.TokenExchangeUseCase;
import com.openat.member.domain.exception.SellerErrorCode;
import com.openat.member.domain.repository.SellerInfoRepository;
import com.openat.member.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RFC 8693 Token Exchange 처리 서비스.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>subject_token(access JWT) 서명·만료 검증, typ=access 확인, memberId 추출</li>
 *   <li>resource URN에서 sellerInfoId 파싱</li>
 *   <li>소유권 + 활성 여부 검증: {@code findActiveByIdAndMemberId}</li>
 *   <li>audience/scope 검증</li>
 *   <li>delegation 모델로 scoped 토큰 발급 (sub=sellerInfoId, act.sub=memberId)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenExchangeService implements TokenExchangeUseCase {

    private final JwtTokenProvider jwtTokenProvider;
    private final SellerInfoRepository sellerInfoRepository;

    @Override
    public TokenExchangeResponse exchange(TokenExchangeRequest request) {
        // 1. subject_token 검증 — 서명·만료·typ=access
        Claims subjectClaims;
        try {
            subjectClaims = jwtTokenProvider.parseAccessToken(request.subjectToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_INVALID_SUBJECT_TOKEN);
        }
        UUID memberId = UUID.fromString(subjectClaims.getSubject());

        // 2. resource URN에서 sellerInfoId 파싱: "urn:openat:seller:{sellerInfoId}"
        String resource = request.resource();
        if (resource == null || !resource.startsWith(TokenExchangeRequest.RESOURCE_SELLER_PREFIX)) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER);
        }
        UUID sellerInfoId;
        try {
            sellerInfoId = UUID.fromString(resource.substring(TokenExchangeRequest.RESOURCE_SELLER_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER);
        }

        // 3. 소유권 + 활성 검증 (soft-delete 제외 — F4 버그 수정)
        sellerInfoRepository.findActiveByIdAndMemberId(sellerInfoId, memberId)
                .orElseThrow(() -> new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER));

        // 4. audience/scope 검증
        if (!TokenExchangeRequest.AUDIENCE_PRODUCT.equals(request.audience())) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE);
        }
        if (!TokenExchangeRequest.SCOPE_PRODUCT_WRITE.equals(request.scope())) {
            throw new BusinessException(SellerErrorCode.SELLER_TOKEN_EXCHANGE_UNSUPPORTED_SCOPE);
        }

        // 5. scoped 토큰 발급 (delegation: sub=sellerInfoId, act.sub=memberId)
        String scopedToken = jwtTokenProvider.createDelegationToken(
                sellerInfoId, memberId, request.audience(), request.scope());

        return TokenExchangeResponse.of(
                scopedToken,
                jwtTokenProvider.getScopedTokenExpireSeconds(),
                request.scope()
        );
    }
}
