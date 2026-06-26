package com.openat.apigateway.openapi;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAPI 집계 대상 서비스 목록 설정.
 *
 * <p>새 서비스 추가 시 코드 변경 없이 application-{profile}.yaml의
 * {@code openapi.aggregate.services} 하위에 {@code 서비스명: URL} 한 줄만 추가하면 된다.
 *
 * <pre>
 * openapi:
 *   aggregate:
 *     services:
 *       member-service: http://localhost:9100/api-docs
 *       settlement-service: http://localhost:9140/api-docs
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "openapi.aggregate")
public class OpenApiAggregateProperties {

    /** key: 서비스명 (Swagger 드롭다운 표시용), value: OpenAPI 문서 URL */
    private Map<String, String> services = new LinkedHashMap<>();

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }
}
