package com.openat.support.auth;

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

  // 게이트웨이가 scoped 토큰을 검증한 뒤 주입하는 신뢰 헤더(X-Seller-Id = sellerInfoId)를 읽는다.
  // 헤더 부재·형식 오류는 게이트웨이를 거치지 않은 비인증 요청이므로 401로 막는다(NPE로 새지 않게).
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
      WebDataBinderFactory binderFactory) {
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
