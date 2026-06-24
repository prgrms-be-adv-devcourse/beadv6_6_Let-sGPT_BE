package com.openat.apigateway.error;

import com.openat.common.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * 게이트웨이 라우팅/필터 단계에서 발생하는 예외(예: 매칭되는 라우트가 없는 경우의
 * {@code org.springframework.cloud.gateway.support.NotFoundException})를 common 모듈의
 * 공통 에러 응답 포맷으로 변환한다.
 *
 * <p>Spring Boot가 기본 등록하는 {@code DefaultErrorWebExceptionHandler}(order -1)보다
 * 먼저 처리되도록 더 낮은 order(-2)를 부여해, apigateway에서는 기본 에러 포맷
 * ({@code {"timestamp":..,"status":..,"error":..}})이 노출되지 않도록 한다.
 *
 * <p>인증/인가 실패(401/403)는 Spring Security가 예외를 가로채 자체
 * authenticationEntryPoint/accessDeniedHandler로 처리하므로 이 핸들러까지 전달되지 않는다.
 * 해당 응답 포맷은 {@code SecurityConfig}에서 별도로 통일한다.
 */
@Slf4j
@Component
@Order(-2)
public class GlobalErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private final ApiErrorResponseWriter responseWriter;

    public GlobalErrorWebExceptionHandler(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            log.warn("[GatewayResponseStatusException] {} : {}", status, ex.getMessage());
            return responseWriter.write(exchange, status, resolveErrorCode(status), rse.getReason());
        }

        if (isConnectFailure(ex)) {
            log.error("[GatewayUpstreamUnavailable] {}", ex.toString());
            return responseWriter.write(exchange, HttpStatus.BAD_GATEWAY, CommonErrorCode.BAD_GATEWAY);
        }

        if (isTimeout(ex)) {
            log.error("[GatewayUpstreamTimeout] {}", ex.toString());
            return responseWriter.write(exchange, HttpStatus.GATEWAY_TIMEOUT, CommonErrorCode.GATEWAY_TIMEOUT);
        }

        log.error("[UnhandledGatewayException]", ex);
        return responseWriter.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, CommonErrorCode.INTERNAL_ERROR);
    }

    /** 다운스트림 서비스가 죽어있거나 연결 자체가 거부된 경우 (connection refused 등) → 502 */
    private boolean isConnectFailure(Throwable ex) {
        for (Throwable cursor = ex; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    /** 다운스트림 서비스가 응답은 하되 너무 느린 경우 (read/connect timeout) → 504.
     * Netty 계열(ReadTimeoutException/WriteTimeoutException/ConnectTimeoutException)까지
     * 폭넓게 잡기 위해 클래스명으로도 보조 판별한다. */
    private boolean isTimeout(Throwable ex) {
        for (Throwable cursor = ex; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof TimeoutException || cursor.getClass().getSimpleName().contains("TimeoutException")) {
                return true;
            }
        }
        return false;
    }

    private CommonErrorCode resolveErrorCode(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> CommonErrorCode.INVALID_INPUT;
            case UNAUTHORIZED -> CommonErrorCode.UNAUTHENTICATED;
            case FORBIDDEN -> CommonErrorCode.FORBIDDEN;
            case NOT_FOUND -> CommonErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> CommonErrorCode.METHOD_NOT_ALLOWED;
            default -> CommonErrorCode.INTERNAL_ERROR;
        };
    }
}
