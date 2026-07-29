package com.openat.member.application.dto;

import java.util.UUID;

/**
 * 판매자 scoped 토큰 발급 요청.
 *
 * <p>게이트웨이가 회원 JWT를 검증하고 X-User-Id(memberId)를 주입하므로
 * 바디에는 스토어(sellerInfoId)와 대상 서비스(audience)만 담는다.
 *
 * @param audience 대상 서비스. {@code openat-product}(기본, 생략 가능) | {@code openat-settlement}.
 *                 생략 시 기존 product 동작을 그대로 유지한다(하위 호환).
 */
public record SellerTokenRequest(UUID sellerInfoId, String audience) {
}
