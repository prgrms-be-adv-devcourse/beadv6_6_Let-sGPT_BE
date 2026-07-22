package com.openat.queue.domain.repository

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.QueueStatusSnapshot
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
     *
     * 대기열 순번(score)은 앱 서버가 계산한 시각이 아니라 Redis 자신의 시계(`TIME`)로 이
     * 스크립트 안에서 원자적으로 찍는다 - 앱 서버 시각(밀리초 해상도)을 쓰면 실제 동시
     * 요청 여러 건이 같은 밀리초에 도착했을 때 ZSET 점수가 타이(tie)나서 Redis가 도착
     * 순서가 아니라 member 문자열(userId) 사전순으로 정렬해버리는 실사용 버그가 있었다
     * (데모에서 실제 재현됨). 그래서 이 메서드는 `now` 파라미터를 받지 않는다.
     * @return 즉시 입장권이 발급됐으면 그 [AdmittedEntry], 대기열에 등록됐을 뿐이면 null
     */
    fun enqueueOrFastAdmit(dropId: String, userId: String, quantity: Int, ttlSeconds: Long): AdmittedEntry?

    /** 현재 순번 스냅샷(+요청 수량). 대기열에 없으면 null. */
    fun ticketOf(dropId: String, userId: String): WaitingTicket?

    /**
     * 상태 폴링(hot path) 한 번에 필요한 모든 값을 단일 원자 실행(1왕복)으로 읽고,
     * [touchHeartbeat]가 true고 대기 중이면 하트비트도 함께 갱신한다(status-snapshot.lua).
     * 예전의 개별 조회 조합(입장권→순번→재고→outstanding→확정→결정, 최대 9왕복)을 대체한다.
     */
    fun statusSnapshotOf(dropId: String, userId: String, now: Instant, touchHeartbeat: Boolean): QueueStatusSnapshot

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

    /**
     * "기다림(WAIT)"을 확정 상태로 기록한다 - 이후 무응답 타임아웃 제거 대상에서 빠진다(정책).
     * @param grantableNowAtConfirm 확정 그 순간의 grantableNow(remaining-outstanding, 0 이상).
     * @param maxAtConfirm 확정 그 순간의 optimisticMax(총재고-확정). null이면 확정 당시 total
     *   미캐시로 값을 몰랐던 경우.
     *
     * 이후 재질의 여부는 "확정 당시와 비교해 둘 중 하나라도 바뀌었는가"로 판단한다
     * (QueueService.resolveStatus 참고) - optimisticMax만으로는 주문 취소(CANCELLED)로
     * 재고가 회복되는 신호를 못 잡기 때문에 grantableNow도 같이 필요하다.
     */
    fun markWaitConfirmed(dropId: String, userId: String, grantableNowAtConfirm: Long, maxAtConfirm: Long?)

    /**
     * DECISION_REQUIRED를 처음 노출하는 순간 "물어본 시각"을 원자적으로 기록한다(이미 기록돼
     * 있으면 기존 값 유지 - 폴링마다 마감이 뒤로 밀리지 않게). 무응답 이탈 처리([sweepDecisionTimeout])의
     * 기준점이자, 클라이언트 카운트다운용 마감 시각(deadline) 계산의 근거다.
     * @return 적용되는 askedAt(epoch ms). WAIT 확정자면 -1(타임아웃 대상 아님).
     */
    fun markAskedIfAbsent(dropId: String, userId: String, now: Instant): Long

    /**
     * 맨 앞(rank 0) 사용자가 DECISION_REQUIRED에 [timeoutMs] 넘게 무응답이면 대기열에서
     * 제거한다(sweep-decision.lua) - 엄격한 FIFO에서 rank 0의 미결정은 큐 전체 정지이므로
     * 필수인 자가치유. WAIT 확정자는 제거하지 않고, 제거 직전 재고가 도착해 몫이 채워진
     * 사람도 원자적 재확인으로 보호한다.
     * @return 제거된 userId, 아무도 제거하지 않았으면 null
     */
    fun sweepDecisionTimeout(dropId: String, now: Instant, timeoutMs: Long): String?

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
