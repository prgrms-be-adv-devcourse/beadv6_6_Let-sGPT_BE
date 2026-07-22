package com.openat.apigateway.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 419(입장권 없음) 응답의 안내 메시지와 본문 버퍼링 상한. 정적 "핫 드롭" 목록은 더 이상 없다 -
 * 모든 드롭에 균일하게 입장권 검증이 적용되므로 별도 대상 목록 설정이 필요 없다.
 *
 * <p>{@code maxBodyBytes}: AdmissionCheck 필터는 dropId를 읽기 위해 POST 본문 전체를
 * 메모리에 버퍼링하는데(평소의 스트리밍 프록시가 이 필터에서만 전체 버퍼링으로 바뀜),
 * 상한이 없으면 대용량 본문 몇 개로 게이트웨이 메모리를 고갈시킬 수 있다. 주문 본문은
 * 수백 바이트 수준이라 8KB면 정상 요청에 오탐 없이 충분하다.
 */
@ConfigurationProperties("admission")
public record AdmissionProperties(
        @DefaultValue("대기열 입장이 필요합니다. POST /api/v1/queues/{dropId}/entry 로 입장한 뒤 "
                + "GET /api/v1/queues/{dropId}/status 로 순번을 폴링하세요.")
        String guidanceMessage,
        @DefaultValue("8192")
        int maxBodyBytes
) {
}
