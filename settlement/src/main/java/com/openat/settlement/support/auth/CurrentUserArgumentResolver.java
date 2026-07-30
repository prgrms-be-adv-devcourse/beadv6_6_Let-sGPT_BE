package com.openat.settlement.support.auth;

import com.openat.common.auth.UserHeaders;
import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        String sellerId = webRequest.getHeader(UserHeaders.SELLER_ID);
        if (!StringUtils.hasText(sellerId)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }

        try {
            return UUID.fromString(sellerId);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
