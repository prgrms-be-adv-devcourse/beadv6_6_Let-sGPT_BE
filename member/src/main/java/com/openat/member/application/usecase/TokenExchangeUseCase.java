package com.openat.member.application.usecase;

import com.openat.member.application.dto.SellerTokenResponse;
import java.util.UUID;

/**
 * 판매자 스토어 범위 scoped 토큰 발급.
 * 게이트웨이가 회원 JWT를 검증하고 X-User-Id(memberId)를 주입하면,
 * member 서비스는 sellerInfoId 소유권을 검증 후 scoped 토큰을 발급한다.
 */
public interface TokenExchangeUseCase {

    SellerTokenResponse issueSellerToken(UUID memberId, UUID sellerInfoId);
}
