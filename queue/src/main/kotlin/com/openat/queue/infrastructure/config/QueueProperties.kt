package com.openat.queue.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * 대기열 튜닝 파라미터를 전부 외부 설정으로 노출한다(하드코딩 금지).
 *
 * 정적 hot-drops 목록은 없다 - 모든 드롭에 균일하게 대기열/입장권 통제가 적용된다
 * (`WaitingQueueRepository.activeDropIds()`가 "어떤 dropId를 살펴봐야 하는지"를 동적으로
 * 알려준다). 경쟁이 없는 드롭은 `enqueueOrFastAdmit`의 즉시 입장 fast path로 대기 없이
 * 통과하므로, 균일 적용에 따른 지연은 실제로 경쟁이 있는 드롭에만 발생한다.
 *
 * `admission.batch-size`는 한 tick에 살펴볼 대기자 후보 상한(maxScan, 성능 보호용 캡)이고,
 * 실제 입장 인원/수량은 그 시점 가용 재고(remaining-outstanding)가 결정한다.
 */
@ConfigurationProperties("queue")
data class QueueProperties(
    val admission: Admission = Admission(),
    val waiting: Waiting = Waiting(),
    val polling: Polling = Polling(),
) {
    data class Admission(
        /**
         * 재고 인지형 admit.lua가 이번 tick에 대기열 앞에서부터 살펴볼 최대 인원(maxScan).
         * 실제 입장 수는 이 값과 무관하게 그 시점 가용 재고(remaining-outstanding)로 결정되고,
         * 이 값은 한 tick에서 Lua가 훑는 후보 범위의 상한(성능 보호용 캡)일 뿐이다.
         */
        @DefaultValue("50") val batchSize: Int = 50,
        /** admit.lua 실행 주기(ms) */
        @DefaultValue("1000") val intervalMs: Long = 1000,
        /** 입장권(admission ticket) 세션 창 TTL(초) - 미소진 시 [AdmittedTicketSweeper]가 회수한다 */
        @DefaultValue("180") val ttlSeconds: Long = 180,
        /** 미소진 입장권(TTL 경과) 회수 스위퍼 주기(ms) */
        @DefaultValue("5000") val sweepIntervalMs: Long = 5000,
    )

    data class Waiting(
        /** 마지막 폴링(하트비트) 이후 이 시간이 지나면 대기열 자리를 회수한다 */
        @DefaultValue("10000") val heartbeatTtlMs: Long = 10000,
        /** 만료된 대기자를 정리하는 스위퍼 주기 */
        @DefaultValue("5000") val sweepIntervalMs: Long = 5000,
    )

    data class Polling(
        /** 클라이언트에 안내할 권장 폴링 주기(ms). 서버가 응답에 실어 클라이언트 폴링 빈도를 제어한다 */
        @DefaultValue("2000") val intervalMs: Long = 2000,
    )
}
