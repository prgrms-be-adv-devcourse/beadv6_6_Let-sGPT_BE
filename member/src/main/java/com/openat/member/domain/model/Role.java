package com.openat.member.domain.model;

public enum Role {
    ROLE_USER,
    ROLE_SELLER,
    ROLE_ADMIN;

    // "ROLE_" 접두사를 뗀 이름.
    public String bareName() {
        return name().substring("ROLE_".length());
    }
}
