package com.openat.search.application.dto;

/** 서비스 반환 DTO(~Info). 프레젠테이션 응답(~Response)과 분리해 계층 경계를 유지한다. */
public record SearchHealthInfo(String service, String status) {}
