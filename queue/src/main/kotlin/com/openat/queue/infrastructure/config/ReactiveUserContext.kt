package com.openat.queue.infrastructure.config

import com.openat.common.auth.UserContext
import com.openat.common.auth.UserHeaders
import com.openat.common.error.CommonErrorCode
import com.openat.common.exception.BusinessException
import org.springframework.http.server.reactive.ServerHttpRequest

/**
 * WebFlux+SSE 전환분: `common`의 [com.openat.common.auth.UserContextFilter]/
 * [com.openat.common.auth.UserContextHolder]는 재사용하지 않는다 - 서블릿 `Filter` +
 * `ThreadLocal` 기반이라 WebFlux(요청 하나가 여러 스레드를 오갈 수 있음)에서는 ThreadLocal이
 * 안전하게 전파된다는 보장이 없다. 그래서 게이트웨이 헤더를 스레드 로컬 경유 없이 그때그때
 * `ServerHttpRequest`에서 직접 읽는다 - 이 모듈은 엔드포인트가 3개뿐이라 매 핸들러에서 직접
 * 읽어도 부담이 없고, Reactor Context 전파 같은 복잡한 대안이 필요 없다.
 */
fun resolveUserContext(request: ServerHttpRequest): UserContext {
    val userId = request.headers.getFirst(UserHeaders.USER_ID)
        ?.takeIf { it.isNotBlank() }
        ?: throw BusinessException(CommonErrorCode.UNAUTHENTICATED, "게이트웨이 사용자 정보가 헤더에 없습니다.")
    val roles = request.headers.getFirst(UserHeaders.USER_ROLES)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { if (it.startsWith("ROLE_")) it.substring(5) else it }
        ?.toSet()
        ?: emptySet()
    return UserContext(userId, roles)
}
