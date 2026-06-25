package com.openat.support.auth;

import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  // TODO: 임시 인증 스텁 - 헤더의 X-User-Id를 검증 없이 주입 (실제 연동 시 이 클래스 삭제 및 UserContext 전환 예정)
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
