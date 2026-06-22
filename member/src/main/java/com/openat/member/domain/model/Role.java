package com.openat.member.domain.model;

public enum Role {
    ROLE_USER,
    ROLE_SELLER;

    /**
     * "ROLE_" 접두사를 뗀 이름.
     * apigateway가 JWT의 {@code roles} 클레임을 읽어 직접 "ROLE_"을 붙여 권한을 만들기 때문에
     * (apigateway {@code SecurityConfig.jwtAuthenticationConverter()} 참고),
     * 토큰 발급 시 클레임에는 이 값("USER"/"SELLER")을 넣어야 "ROLE_ROLE_SELLER"처럼 중복되지 않는다.
     */
    public String bareName() {
        return name().substring("ROLE_".length());
    }
}
