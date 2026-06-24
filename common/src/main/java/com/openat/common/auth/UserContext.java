package com.openat.common.auth;

import java.util.Set;

public record UserContext(String userId, Set<String> roles) {

    public boolean hasRole(String role) {
        String normalized = role.startsWith("ROLE_") ? role.substring(5) : role;
        return roles.contains(normalized);
    }
}