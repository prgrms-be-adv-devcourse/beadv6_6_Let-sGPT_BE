package com.openat.support.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 판매자 주체(활성 스토어)의 식별자 {@code sellerInfoId}를 컨트롤러 파라미터로 주입한다.
 *
 * <p>상품·드롭은 회원이 아니라 <b>스토어(SellerInfo)</b>에 귀속된다. 게이트웨이가 판매자 토큰의 스토어 스코프를 검증해 전달하는 값을 신뢰하며,
 * write·본인 목록(/me)의 소유·필터 기준으로 쓴다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}
