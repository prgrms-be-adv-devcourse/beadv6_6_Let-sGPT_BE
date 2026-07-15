package com.openat.apigateway.error;

import com.openat.common.error.ErrorCode;
import com.openat.common.error.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * apigateway에서 발생하는 모든 에러를 common 모듈의 공통 에러 응답 포맷
 * ({@link ErrorResponse}, {@code { "error": "CODE", "message": "..." }})으로 내려주기 위한 writer.
 *
 * <p>WebFlux는 게이트웨이 라우팅/시큐리티 단계에서 {@code @RestControllerAdvice}가 적용되지 않으므로
 * (애너테이션 기반 컨트롤러가 없음), {@link GlobalErrorWebExceptionHandler}와 시큐리티의
 * authenticationEntryPoint/accessDeniedHandler에서 이 writer를 공통으로 사용한다.
 *
 * <p>Spring Boot 4부터 기본 자동설정 ObjectMapper가 Jackson 3({@code tools.jackson.databind})
 * 타입이라 (spring-boot-starter-webflux → spring-boot-starter-jackson), Jackson 2
 * ({@code com.fasterxml.jackson.databind}) 타입을 주입받으려 하면 빈을 찾지 못해 기동이 실패한다.
 * 의도적으로 Jackson 3 타입을 사용한다.
 */
@Slf4j
@Component
public class ApiErrorResponseWriter {

    private static final byte[] FALLBACK_BODY =
            "{\"error\":\"INTERNAL_ERROR\",\"message\":\"에러 응답을 생성하지 못했습니다.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;

    public ApiErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        return write(exchange, status, ErrorResponse.of(errorCode));
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode, String message) {
        String resolvedMessage = (message == null || message.isBlank()) ? errorCode.getMessage() : message;
        return write(exchange, status, ErrorResponse.of(errorCode, resolvedMessage));
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String errorCode, String message) {
        return write(exchange, status, new ErrorResponse(errorCode, message));
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorResponse body) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JacksonException e) {
            // Jackson 3부터 직렬화 예외는 unchecked(RuntimeException)이라 명시적으로 잡아줘야
            // 컴파일러가 강제하지 않아도 직렬화 실패 시 요청 처리가 통째로 끊기지 않는다.
            log.error("[ApiErrorResponseWriter] 에러 응답 직렬화 실패", e);
            bytes = FALLBACK_BODY;
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
