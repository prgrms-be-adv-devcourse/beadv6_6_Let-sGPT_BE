package com.openat.member.application.dto;

import java.util.UUID;

/**
 * 판매자 scoped 토큰 발급 요청.
 *
 * <p>게이트웨이가 회원 JWT를 검증하고 X-User-Id(memberId)를 주입하므로
 * 바디에는 스토어(sellerInfoId)만 담는다.
 */
public record SellerTokenRequest(UUID sellerInfoId) {
}
