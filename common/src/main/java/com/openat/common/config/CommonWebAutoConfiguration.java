package com.openat.common.config;

import com.openat.common.auth.CurrentUserArgumentResolver;
import com.openat.common.auth.UserContextFilter;
import com.openat.common.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * common 모듈이 제공하는 웹 공통 빈을 등록하는 자동설정.
 *
 * <p>{@code project(":common")} 의존만 추가하면 각 서비스의 컴포넌트 스캔 범위와
 * 무관하게 {@link GlobalExceptionHandler}가 활성화된다.
 *
 * <p>{@link WebMvcConfigurer}가 클래스패스에 없는 리액티브(WebFlux) 모듈(예: apigateway)이
 * {@code project(":common")}을 의존해도 이 자동설정이 로드되지 않도록 가드한다.
 */
@AutoConfiguration
@ConditionalOnClass(WebMvcConfigurer.class)
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration implements WebMvcConfigurer {

    @Bean
    public FilterRegistrationBean<UserContextFilter> userContextFilter() {
        FilterRegistrationBean<UserContextFilter> registration =
                new FilterRegistrationBean<>(new UserContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

}
