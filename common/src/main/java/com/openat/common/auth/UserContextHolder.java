package com.openat.common.auth;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;

import java.util.Set;

public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext context) {
        HOLDER.set(context);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    /**
     * 컨텍스트가 없으면 {@link BusinessException}(401 UNAUTHENTICATED)을 던진다.
     * {@code @CurrentUser}는 "인증 필수"를 의미하므로, {@link com.openat.common.auth.CurrentUserArgumentResolver}도
     * 이 메서드를 사용해 동일한 예외로 통일한다.
     */
    public static UserContext require() {
        UserContext context = HOLDER.get();
        if (context == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED, "게이트웨이 사용자 정보가 컨텍스트에 없습니다.");
        }
        return context;
    }

    public static String currentUserId() {
        return require().userId();
    }

    /**
     * 현재 요청 사용자의 역할 목록을 반환한다.
     * 컨텍스트가 없으면 {@link #require()}와 동일하게 401 {@link BusinessException}을 던진다.
     */
    public static Set<String> currentRoles() {
        return require().roles();
    }

    /**
     * 현재 요청 사용자가 주어진 역할을 가지고 있는지 확인한다.
     * 컨텍스트가 없으면 {@link #require()}와 동일하게 401 {@link BusinessException}을 던진다.
     */
    public static boolean currentUserHasRole(String role) {
        return require().hasRole(role);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
