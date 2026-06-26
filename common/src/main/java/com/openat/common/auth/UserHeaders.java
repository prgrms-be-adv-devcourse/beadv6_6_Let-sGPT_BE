package com.openat.common.auth;

public final class UserHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";
    /** RFC 8693 delegation: 게이트웨이가 scoped 토큰의 sub(sellerInfoId)를 검증 후 주입 */
    public static final String SELLER_ID = "X-Seller-Id";

    private UserHeaders() {
    }
}