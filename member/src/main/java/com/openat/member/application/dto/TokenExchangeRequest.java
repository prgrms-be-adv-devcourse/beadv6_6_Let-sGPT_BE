package com.openat.member.application.dto;

/**
 * RFC 8693 §2.1 token exchange 요청.
 * {@code application/x-www-form-urlencoded} 폼 파라미터를 매핑한다.
 *
 * <p>현재 지원 값:
 * <ul>
 *   <li>grant_type: {@code urn:ietf:params:oauth:grant-type:token-exchange}</li>
 *   <li>subject_token_type: {@code urn:ietf:params:oauth:token-type:jwt}</li>
 *   <li>requested_token_type: {@code urn:ietf:params:oauth:token-type:jwt}</li>
 *   <li>audience: {@code openat-product}</li>
 *   <li>scope: {@code product:write}</li>
 *   <li>resource: {@code urn:openat:seller:{sellerInfoId}} — 테넌트 선택 (비표준 확장)</li>
 * </ul>
 */
public record TokenExchangeRequest(
        String grantType,
        String subjectToken,
        String subjectTokenType,
        String requestedTokenType,
        String audience,
        String scope,
        String resource
) {

    public static final String GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String TOKEN_TYPE_JWT = "urn:ietf:params:oauth:token-type:jwt";
    public static final String AUDIENCE_PRODUCT = "openat-product";
    public static final String SCOPE_PRODUCT_WRITE = "product:write";
    /** resource URN 접두사. sellerInfoId는 마지막 세그먼트. */
    public static final String RESOURCE_SELLER_PREFIX = "urn:openat:seller:";
}
