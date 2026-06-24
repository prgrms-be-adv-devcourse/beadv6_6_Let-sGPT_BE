package com.openat.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class UserContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String userId = request.getHeader(UserHeaders.USER_ID);
        if (userId != null && !userId.isBlank()) {
            Set<String> roles = parseRoles(request.getHeader(UserHeaders.USER_ROLES));
            UserContextHolder.set(new UserContext(userId, roles));
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();   // 스레드 풀 재사용 시 누수 방지
        }
    }

    private Set<String> parseRoles(String rawRoles) {
        if (rawRoles == null || rawRoles.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rawRoles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                .collect(Collectors.toUnmodifiableSet());
    }
}