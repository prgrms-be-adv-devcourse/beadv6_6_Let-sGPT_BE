package com.openat.apigateway.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 419(입장권 없음) 응답의 안내 메시지. 정적 "핫 드롭" 목록은 더 이상 없다 - 모든 드롭에
 * 균일하게 입장권 검증이 적용되므로 별도 대상 목록 설정이 필요 없다.
 */
@ConfigurationProperties("admission")
public record AdmissionProperties(
        @DefaultValue("대기열 입장이 필요합니다. POST /api/v1/queues/{dropId}/entry 로 입장한 뒤 "
                + "GET /api/v1/queues/{dropId}/status 로 순번을 폴링하세요.")
        String guidanceMessage
) {
}
