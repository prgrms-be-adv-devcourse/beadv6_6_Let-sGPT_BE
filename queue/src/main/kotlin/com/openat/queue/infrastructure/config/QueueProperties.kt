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
 * 입장은 엄격한 FIFO다 - 맨 앞(지금 차례인) 사람 몫이 그 시점 가용 재고로 안 되면 뒷사람은
 * 아예 보지 않고 그 자리에서 멈춘다(새치기 없음, "먼저 온 사람이 자리를 지킨다"). 그래서
 * `admission.batch-size`(maxScan)는 이제 정상적인 스캔 범위가 아니라, 이미 입장권을 보유한
 * 비정상 유령 항목이 연달아 있을 때만 관여하는 방어용 상한일 뿐이다.
 */
@ConfigurationProperties("queue")
data class QueueProperties(
    val admission: Admission = Admission(),
    val waiting: Waiting = Waiting(),
    val polling: Polling = Polling(),
    val entry: Entry = Entry(),
    val decision: Decision = Decision(),
    val sse: Sse = Sse(),
    val concurrency: Concurrency = Concurrency(),
) {
    data class Entry(
        /**
         * 진입 요청 수량의 전역 상한(안전망). 프론트가 1~5로 제한하지만 API 직접 호출로 우회
         * 가능하므로 서버에서 강제한다 - 상한 없는 수량(예: 999999)은 엄격한 FIFO에서 rank 0
         * 도달 시 대기열 전체를 무기한 정지시키는 벡터가 된다. 드롭별 정밀 정책은 product의
         * `limitPerUser`(drop 해시)를 추가로 적용한다(QueueService.enter).
         * 정책 변경 시 env `QUEUE_ENTRY_MAX_QUANTITY`만 바꾸면 된다(재배포 불필요).
         */
        @DefaultValue("5") val maxQuantity: Int = 5,
    )

    data class Decision(
        /**
         * DECISION_REQUIRED를 처음 노출한 뒤 이 시간 안에 응답(WAIT/PARTIAL/GIVE_UP)이 없으면
         * 무응답 이탈로 간주해 대기열에서 제거한다 - 엄격한 FIFO에서 rank 0의 미결정은 큐 전체
         * 정지이므로 필수 장치. WAIT을 명시적으로 선택한 사람은 제거 대상이 아니다(정책상 앞
         * 순번 유지). 마감 시각(deadline)은 상태 응답에 실어 클라이언트가 카운트다운을 보여준다.
         */
        @DefaultValue("30000") val timeoutMs: Long = 30000,
    )

    data class Admission(
        /**
         * admit.lua가 엄격한 FIFO로 멈추기 전, "이미 입장권을 보유한 비정상 유령 항목"을
         * 연달아 건너뛸 수 있는 최대 횟수(방어용 상한, maxScan). 정상 흐름에서는 맨 앞
         * 정상 대기자를 만나는 즉시 스캔이 끝나므로 이 값에 도달할 일이 거의 없다.
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
        /**
         * SSE 연결이 전부 끊긴 뒤(같은 dropId+userId의 마지막 구독이 취소된 뒤) 실제로 대기열
         * 자리를 회수하기까지 기다리는 유예 시간(ms). 지하철 터널 진입, 폰 화면 잠금/앱 전환,
         * 탭 재연결처럼 "곧 다시 붙는" 끊김과 "진짜로 나간" 끊김을 SSE 연결 하나만 보고는 구분할
         * 수 없다 - 유예 시간 안에 같은 dropId+userId로 새 구독이 들어오면 예약된 회수를
         * 취소한다. heartbeatTtlMs(10000)보다 충분히 짧게 잡아야 이 유예 자체가 새로운 지연
         * 이탈 처리 창이 되지 않는다(진짜로 안 돌아오는 사람은 결국 heartbeatTtlMs 스위퍼가
         * 잡아준다 - 이 유예는 "더 빠른 회수"라는 최적화의 안전판일 뿐, 유일한 수단이 아니다).
         */
        @DefaultValue("5000") val reconnectGraceMs: Long = 5000,
    )

    data class Polling(
        /** 클라이언트에 안내할 권장 폴링 주기(ms). 서버가 응답에 실어 클라이언트 폴링 빈도를 제어한다 */
        @DefaultValue("2000") val intervalMs: Long = 2000,
    )

    data class Sse(
        /** WebFlux+SSE 전환분: keep-alive 코멘트 이벤트 주기(ms) - idle 커넥션 타임아웃 방지 +
         * Pub/Sub 신호 유실 시의 최후 재확인 안전망을 겸한다. */
        @DefaultValue("8000") val keepaliveMs: Long = 8000,
    )

    data class Concurrency(
        /**
         * 동시 처리 중인 요청 수 상한(ConcurrencyLimitFilter가 강제). MVC/Tomcat은 스레드
         * 풀(운영 기준 50개)이 그 이상 몰리는 요청을 처리 시작 전 단계(OS 커널의 가벼운 accept
         * backlog)에 묶어둬서 "동시 처리 요청 수 제한"을 부수 효과로 갖는다 - 그래서 몰려도
         * 힙을 거의 안 쓴다. WebFlux/Netty에는 이런 상한이 기본적으로 없어(들어오는 연결을
         * 원칙적으로 전부 받아들여 처리를 시작) 아주 작은 힙(운영 파드 기준 약 220MB)에서
         * 동시접속이 수천 단위로 몰리면 각 요청이 물고 있는 처리 상태가 쌓여 OutOfMemoryError로
         * 이어질 수 있다(실측: t3.large 근사 환경, 동접 약 5,000~6,000명에서 3회 재현 -
         * queue/loadtest/three-stage-story.md §4-4 "정직하게 밝히는 한계" 참고). 이 필터는
         * MVC가 부수 효과로 얻던 그 보호를 명시적으로 재현한다 - 상한을 넘는 요청은 처리를
         * 시작하지 않고 즉시 429로 거절해, 서비스 전체가 죽는 대신 초과분만 지연 없이 실패하는
         * 쪽을 택한다. 기본값 2,000은 실측 붕괴 지점(약 5,000)보다 충분히 낮게 잡은 안전
         * 마진이다 - 운영 트래픽 프로파일에 맞춰 조정 가능(`QUEUE_CONCURRENCY_MAX_IN_FLIGHT`).
         */
        @DefaultValue("2000") val maxInFlightRequests: Int = 2000,
    )
}
