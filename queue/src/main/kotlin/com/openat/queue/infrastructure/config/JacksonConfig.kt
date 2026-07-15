package com.openat.queue.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot 4는 클래식 Jackson2(`com.fasterxml.jackson.databind.ObjectMapper`) 빈을
 * 자동 등록하지 않는다(Jackson 3 `tools.jackson`이 기본 우선순위를 가져감) - 실제로 이 빈이
 * 없어서 `OrderCompletedConsumer`가 기동 실패하는 것을 라이브 부팅 테스트로 확인했다.
 * order/product 모듈의 동일한 `JacksonConfig` 관례를 그대로 따른다.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun objectMapper(): ObjectMapper {
        val mapper = ObjectMapper()
        mapper.registerModule(JavaTimeModule())
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        return mapper
    }
}
