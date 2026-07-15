package com.openat.queue.domain.repository

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.WaitingTicket
import java.time.Instant

/**
 * 대기열 도메인이 소유하는 출력 포트. 구현은 infrastructure.persistence에 둔다.
 *
 * 통신 방식(MVC/폴링 vs WebFlux/SSE)과 무관한 순수 도메인 포트로 분리해
 * 이후 통신 어댑터를 교체해도(전면 재작성 없이) 이 계약은 그대로 재사용된다.
 */
interface WaitingQueueRepository {

    /**
     * 대기열이 비어 있고(새치기 없음) 요청 수량만큼 재고가 즉시 가용하면 대기열에 넣지 않고
     * 그 자리에서 원자적으로 입장권을 발급한다("즉시 입장 fast path" - 정적 hot-drops 목록이
     * 없어도 무경쟁 드롭은 admit.lua의 스케줄러 tick을 기다리지 않고 바로 통과시키기 위함).
     * 그 조건에 못 미치면(이미 대기자가 있거나 가용 재고 부족) 평범하게 대기열(ZSET)에
     * 등록한다(이미 있으면 무시, NX. 요청 수량과 하트비트는 이미 대기 중이어도 매번 갱신 -
     * 재호출로 수량을 바꿀 수 있게 허용). 어느 경우든 이 dropId를 [activeDropIds]에 등록한다.
     * @return 즉시 입장권이 발급됐으면 그 [AdmittedEntry], 대기열에 등록됐을 뿐이면 null
     */
    fun enqueueOrFastAdmit(dropId: String, userId: String, quantity: Int, ttlSeconds: Long, now: Instant): AdmittedEntry?

    /** 현재 순번 스냅샷(+요청 수량). 대기열에 없으면 null. */
    fun ticketOf(dropId: String, userId: String): WaitingTicket?

    /** 폴링 시 마지막 응답 시각(하트비트)을 갱신해 이탈 판정 TTL을 늦춘다. */
    fun touchHeartbeat(dropId: String, userId: String, now: Instant)

    /** 현재 대기 인원(ZCARD). 성능 측정 하네스(Phase 4)의 Gauge 지표용. */
    fun sizeOf(dropId: String): Long

    /** 입장권(admission ticket)이 발급되어 있으면 그 수량을, 없으면 null을 반환한다(소진하지 않음 - peek). */
    fun admittedQuantityOf(dropId: String, userId: String): Int?

    /**
     * 하트비트가 끊긴(마지막 폴링이 `now - heartbeatTtl` 이전인) 대기자를
     * 대기열/하트비트/수량 해시 전부에서 제거한다.
     * @return 회수된 인원 수
     */
    fun sweepExpired(dropId: String, now: Instant, heartbeatTtlMs: Long): Long

    /**
     * 대기열 맨 앞(지금 차례인 사람)부터 순서대로, product의 실재고(remaining)에서 현재
     * 미소진 입장권 수량(outstanding)을 뺀 만큼만 원자적으로 입장 처리한다(ZSET pop +
     * 입장권 발급 + outstanding 갱신, 재고 인지형 admit.lua 단일 실행). **엄격한 FIFO**:
     * 요청 수량이 그 시점 가용량보다 큰 사람을 만나면 그 자리에서 즉시 멈춘다 - 뒷사람에게
     * 순서를 넘기지 않는다("먼저 온 사람이 자리를 지킨다"). [maxScan]은 정상 스캔 범위가
     * 아니라, 이미 입장권을 보유한 비정상 유령 항목이 연달아 있을 때만 관여하는 방어용
     * 상한이다(admit.lua 헤더 주석 참고).
     * 멀티 파드가 동시에 호출해도 중복 입장이 발생하지 않는다.
     * @return 이번 tick에서 입장 처리된 (userId, quantity) 목록
     */
    fun admitBatch(dropId: String, maxScan: Int, ttlSeconds: Long): List<AdmittedEntry>

    /**
     * 발급됐지만 아직 소진되지 않은 입장권 중 TTL이 지난 것을 회수하고, 그만큼 outstanding을
     * 되돌린다(자리를 잃음 - 방치된 입장권이 영원히 재고를 묶어두지 않도록 하는 자가치유).
     * @return 회수된 입장권 수
     */
    fun sweepAdmittedTickets(dropId: String, now: Instant): Long

    /** 현재 미소진 입장권 수량 합(outstanding). admit.lua가 쓰는 값과 동일한 것을 애플리케이션
     * 계층에서도 읽어 `available = remaining - outstanding`을 status 응답용으로 계산한다. */
    fun outstandingOf(dropId: String): Long

    /** 이 사용자가 "기다림(WAIT)"을 이미 확정했는지(같은 질문을 반복하지 않기 위함) - peek. */
    fun hasConfirmedWait(dropId: String, userId: String): Boolean

    /** "기다림(WAIT)"을 확정 상태로 기록한다. */
    fun markWaitConfirmed(dropId: String, userId: String)

    /**
     * 특정 사용자 한 명을 그 순간의 가용 재고만큼만 즉시 원자적으로 입장 처리한다("부분구매"
     * 결정). 재확인 결과 가용량이 0 이하면 아무 것도 하지 않고 null을 반환한다(폴링 시점에
     * 보여준 grantableNow는 참고값일 뿐, 실제 발급량은 이 호출 시점에 다시 계산됨).
     *
     * 엄격한 FIFO 정책상 지금 맨 앞(rank 0)이 아닌 사용자는 원자적으로 거부한다(null) -
     * 그렇지 않으면 대기열 뒤쪽 사용자가 자기 순서를 건너뛰고 가용 재고를 가로챌 수 있다.
     * @return 실제로 발급된 (userId, quantity) - 발급 불가(맨 앞이 아니거나 재고 부족)면 null
     */
    fun admitSingle(dropId: String, userId: String, ttlSeconds: Long): AdmittedEntry?

    /** 대기열/하트비트/수량/결정상태 전부에서 이 사용자를 제거한다("포기" 선택). */
    fun removeFromQueue(dropId: String, userId: String)

    /**
     * 정적 hot-drops 목록을 대체하는 동적 발견 레지스트리 - 현재 대기자가 있거나 미소진
     * 입장권이 남아있어 스케줄러/스위퍼가 살펴봐야 하는 dropId 전체를 반환한다.
     */
    fun activeDropIds(): Set<String>

    /**
     * 이 dropId가 완전히 유휴 상태(대기자 0명 + 미소진 입장권 0개)면 [activeDropIds]에서
     * 제거한다. [ExpiredWaiterSweeper]가 각 dropId를 정리한 직후 호출해 레지스트리가
     * 무한히 커지지 않게 한다.
     * @return 실제로 제거됐으면 true
     */
    fun pruneIfIdle(dropId: String): Boolean
}
