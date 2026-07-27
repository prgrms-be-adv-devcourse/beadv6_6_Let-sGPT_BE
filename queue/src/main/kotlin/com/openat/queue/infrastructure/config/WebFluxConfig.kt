package com.openat.queue.infrastructure.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer

/** [ReactiveCurrentUserArgumentResolver]를 컨트롤러 인자 해석기로 등록한다. */
@Configuration
class WebFluxConfig : WebFluxConfigurer {
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(ReactiveCurrentUserArgumentResolver())
    }
}
