package com.openat.common.config;

import com.openat.common.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * common 모듈이 제공하는 웹 공통 빈을 등록하는 자동설정.
 *
 * <p>{@code project(":common")} 의존만 추가하면 각 서비스의 컴포넌트 스캔 범위와
 * 무관하게 {@link GlobalExceptionHandler}가 활성화된다.
 */
@AutoConfiguration
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {
}
