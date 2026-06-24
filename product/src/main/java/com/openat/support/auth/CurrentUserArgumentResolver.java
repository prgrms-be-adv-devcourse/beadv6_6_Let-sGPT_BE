package com.openat.support.auth;

import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  // TODO: 회원 도메인/게이트웨이 인증 연동 전까지 X-User-Id 헤더를 신뢰해 사용자 식별자로 사용 -> JWT 컨텍스트 추출로 교체
  private static final String USER_ID_HEADER = "X-User-Id";

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
    return UUID.fromString(webRequest.getHeader(USER_ID_HEADER));
  }
}
