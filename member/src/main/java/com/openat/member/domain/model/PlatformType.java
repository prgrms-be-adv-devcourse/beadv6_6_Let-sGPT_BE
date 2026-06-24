package com.openat.member.domain.model;

/**
 * 회원의 가입 경로(자체가입/소셜로그인 제공자)를 나타낸다.
 */
public enum PlatformType {
    LOCAL,
    KAKAO,
    GOOGLE,
    NAVER
}
