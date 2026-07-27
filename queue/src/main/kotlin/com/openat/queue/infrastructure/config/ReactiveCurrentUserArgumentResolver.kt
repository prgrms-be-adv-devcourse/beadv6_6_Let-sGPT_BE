package com.openat.queue.infrastructure.config

import com.openat.common.auth.CurrentUser
import com.openat.common.auth.UserContext
import org.springframework.core.MethodParameter
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.result.method.SyncHandlerMethodArgumentResolver
import org.springframework.web.server.ServerWebExchange

/**
 * WebFlux+SSE 전환분: MVC의 `CurrentUserArgumentResolver`(common 모듈)는 서블릿
 * `HandlerMethodArgumentResolver`라 WebFlux 컨트롤러에는 적용되지 않는다. `@CurrentUser`
 * 어노테이션 타입 자체는 그대로 재사용하고, 리액티브 전용 리졸버만 이 모듈에 새로 둔다
 * (컨트롤러 코드가 `@CurrentUser UserContext` 스타일을 그대로 쓸 수 있게 유지하기 위함).
 * 헤더만 동기로 읽으면 되므로 `SyncHandlerMethodArgumentResolver`로 충분하다(I/O 없음).
 */
class ReactiveCurrentUserArgumentResolver : SyncHandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) &&
            parameter.parameterType == UserContext::class.java

    override fun resolveArgumentValue(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange,
    ): Any = resolveUserContext(exchange.request)
}
