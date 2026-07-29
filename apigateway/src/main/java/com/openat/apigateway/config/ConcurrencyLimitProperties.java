package com.openat.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 게이트웨이가 동시에 "처리 중"으로 들고 있을 수 있는 요청 수의 상한.
 *
 * <p>배경(실측): MVC/Tomcat은 스레드 풀(운영 기준 50개)이 그 이상 몰리는 요청을 처리 시작 전
 * 단계(OS 커널의 가벼운 accept backlog)에 묶어둬서 "동시 처리 요청 수 제한"을 부수 효과로 갖는다.
 * WebFlux/Netty에는 이 상한이 기본적으로 없어, 들어오는 연결을 원칙적으로 전부 받아들여 처리를
 * 시작하고 각 요청의 처리 상태를 힙에 물고 있는다. 힙이 작으면 이게 그대로 OOM으로 이어진다.
 *
 * <p>이 게이트웨이에서 직접 재현했다 - 운영 파드와 동일한 힙 상한(320Mi × {@code
 * MaxRAMPercentage=50%} ≈ 160MB, k8s/base/20-apigateway.yaml)과 2vCPU 근사 환경에서 동시접속을
 * 1,000 → 6,000명으로 올리자 <b>3회 시행 전부 {@code OutOfMemoryError}로 다운</b>됐다(호스트
 * 메모리는 3회 내내 6% 사용으로 건강했으므로 환경 요인이 아님이 확인됨). queue 모듈이 겪은 것과
 * 완전히 동일한 현상이며, 게이트웨이는 <b>메모리 한도가 더 작고(320Mi vs queue 400Mi) 모든
 * 트래픽의 단일 진입점</b>이라 위험도가 오히려 더 높다.
 *
 * <p>기본값 2,000은 실측 붕괴 지점(약 5,000)보다 충분히 낮게 잡은 안전 마진이다. 운영 트래픽
 * 프로파일에 맞춰 {@code GATEWAY_CONCURRENCY_MAX_IN_FLIGHT}로 조정할 수 있다.
 *
 * <p>자세한 측정 과정과 수치는 {@code queue/loadtest/three-stage-story.md} 참고.
 */
@ConfigurationProperties("gateway.concurrency")
public record ConcurrencyLimitProperties(
        @DefaultValue("2000")
        int maxInFlightRequests,

        /**
         * SSE({@code /status/stream}) 전용 동시 연결 수 상한. SSE는 연결 수명 내내(게이트웨이
         * 라우트 metadata의 response-timeout 10분 동안) 슬롯을 점유하는 성격이라, 일반 API와
         * 같은 카운터를 공유하면 SSE 구독자가 상한을 채운 순간부터는 GIVE_UP 같은 상태 변경
         * 요청도 429로 거절돼 사용자가 스스로 자리를 반납할 방법이 없어진다(리뷰 지적). 별도
         * 카운터로 분리해 제어 API가 SSE 포화와 무관하게 항상 처리되도록 한다. 기본값은 전체
         * 상한보다 낮게 잡아 제어 API 몫을 항상 남겨둔다.
         */
        @DefaultValue("1500")
        int sseMaxInFlightRequests
) {
}
