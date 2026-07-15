package com.openat.member.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Boot 4.1은 기본 ObjectMapper 빈을 Jackson 3(tools.jackson.*)으로 자동등록해, outbox/Kafka
// 메시지 직렬화에 쓰는 Jackson 2(com.fasterxml.jackson.databind) ObjectMapper는 직접 빈으로 등록해야 함
// (order/payment/product 모듈과 동일한 원인·동일한 해결책).
// JavaTimeModule 등록은 WishlistChangedEvent.occurredAt(Instant) 직렬화에 필수.
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
