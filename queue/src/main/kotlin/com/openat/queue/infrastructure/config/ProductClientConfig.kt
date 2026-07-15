package com.openat.queue.infrastructure.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * product의 총재고 조회(`GET /drops/{dropId}`) 전용 클라이언트. order 모듈의 Feign 관례
 * (`services.product.url`, local=localhost:9110/compose=env)를 그대로 재사용한다. 드롭당
 * 1회만 호출하고 결과를 캐싱하므로([ConfirmedSalesRedisRepository]) Feign 도입 없이
 * Spring 6 `RestClient`(spring-boot-starter-web에 이미 포함)만으로 충분하다.
 */
@Configuration
class ProductClientConfig(
    @Value("\${services.product.url}") private val productBaseUrl: String,
) {

    @Bean
    fun productRestClient(): RestClient = RestClient.builder()
        .baseUrl(productBaseUrl)
        .build()
}
