package com.openat.queue.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient

/**
 * product의 총재고 조회(`GET /drops/{dropId}`) 전용 클라이언트. order 모듈의 Feign 관례
 * (`services.product.url`, local=localhost:9110/compose=env)를 그대로 재사용한다. 드롭당
 * 1회만 호출하고 결과를 캐싱하므로([ConfirmedSalesRedisRepository]) Feign 도입 없이
 * Spring 6 `RestClient`(spring-boot-starter-web에 이미 포함)만으로 충분하다.
 *
 * 버그 이력(반드시 읽을 것) - 라이브 데모에서 실제로 재현됨: 재고 부족으로 DECISION_REQUIRED가
 * 처음 발생하는 요청(= `ConfirmedSalesRepository.totalOf()`의 REST 폴백이 처음 실행되는
 * 경로)에서 `/entry`·`/status`가 500(INTERNAL_ERROR)으로 죽었다.
 *
 * 원인: 이 프로젝트는 Spring Boot 4가 기본으로 끌어오는 Jackson 3(`tools.jackson`)와,
 * `JacksonConfig`가 등록하는 클래식 Jackson 2(`com.fasterxml.jackson`)가 클래스패스에
 * 동시에 존재한다(직접 확인함: `:queue:dependencies`에 `tools.jackson.core:jackson-databind:3.1.4`와
 * `com.fasterxml.jackson.core:jackson-databind:2.21.4`가 둘 다 있음). 이 모듈에서는(이유는
 * 더 확인 필요하지만) Boot가 자동구성한 `RestClient.Builder` 빈 자체가 아예 제공되지 않아서
 * (`payment`의 `OrderClientConfig`처럼 주입받으려 하면 기동 자체가
 * `NoSuchBeanDefinitionException`으로 실패하는 것을 직접 확인함) 정적 `RestClient.builder()`를
 * 쓸 수밖에 없는데, 그러면 메시지 컨버터가 모호하게 결정돼 `DropTotalQuantityResponse`의
 * `@JsonIgnoreProperties(ignoreUnknown=true)`(클래식 Jackson 2 애노테이션)가 무시되고
 * product의 실제 응답(다른 필드 다수 포함)을 역직렬화하다 잡히지 않는 예외를 던졌다.
 *
 * 해결: Boot 자동구성에 기대지 않고, 이 RestClient 전용으로 [MappingJackson2HttpMessageConverter]를
 * `JacksonConfig`가 등록한 클래식 [ObjectMapper]로 직접 만들어 명시적으로 붙인다 - 어떤
 * 자동구성이 활성화되든 상관없이 이 클라이언트는 항상 `DropTotalQuantityResponse`의
 * 애노테이션과 호환되는 컨버터만 쓰게 된다.
 */
@Configuration
class ProductClientConfig(
    @Value("\${services.product.url}") private val productBaseUrl: String,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun productRestClient(): RestClient = RestClient.builder()
        .baseUrl(productBaseUrl)
        .messageConverters { converters ->
            converters.removeIf { it is MappingJackson2HttpMessageConverter }
            converters.add(0, MappingJackson2HttpMessageConverter(objectMapper))
        }
        .build()
}
