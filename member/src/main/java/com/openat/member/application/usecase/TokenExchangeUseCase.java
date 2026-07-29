package com.openat.member.application.usecase;

import com.openat.member.application.dto.SellerTokenResponse;
import java.util.UUID;

/**
 * 판매자 스토어 범위 scoped 토큰 발급.
 * 게이트웨이가 회원 JWT를 검증하고 X-User-Id(memberId)를 주입하면,
 * member 서비스는 sellerInfoId 소유권을 검증 후 scoped 토큰을 발급한다.
 */
public interface TokenExchangeUseCase {

    /**
     * @param audience 대상 서비스. {@code null}/blank이면 하위 호환을 위해
     *                 {@code TokenExchangeRequest.AUDIENCE_PRODUCT}로 취급한다.
     *                 지원하지 않는 값이면 {@code SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE}로 거부한다.
     */
    SellerTokenResponse issueSellerToken(UUID memberId, UUID sellerInfoId, String audience);
}
