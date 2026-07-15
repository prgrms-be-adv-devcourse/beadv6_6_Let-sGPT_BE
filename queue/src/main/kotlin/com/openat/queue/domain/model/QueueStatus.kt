package com.openat.queue.domain.model

/**
 * 대기열 진입 통제 판정 결과.
 *
 * - [READY]: 입장권(admission ticket)이 발급되어 즉시 보호 API 호출 가능.
 * - [WAITING]: 아직 대기열 ZSET에 남아 순번을 기다리는 중.
 * - [NOT_IN_QUEUE]: 대기열에 진입한 적이 없거나(또는 이탈 TTL 만료로 회수됨).
 * - [SOLD_OUT]: 대기 중이었으나 드롭이 마감(closeAt 경과)되어 더 이상 입장할 수 없게 됨.
 *   입장에 성공한 사람은 재고 인지형 admit.lua가 가용 재고 이하만 통과시키므로 이 상태를
 *   보지 않는다 - 순번에 못 든 채로 판매 기간이 끝난 사람만 이 상태를 받는다.
 * - [DECISION_REQUIRED]: 요청 수량만큼 지금 당장은 못 받는 상황이라 선택이 필요함
 *   (`grantableNow`/`optimisticMax` 참고). [com.openat.queue.domain.model.DecisionChoice]로 응답한다.
 */
enum class QueueStatus {
    READY,
    WAITING,
    NOT_IN_QUEUE,
    SOLD_OUT,
    DECISION_REQUIRED,
}

/**
 * 대기 중인 사용자의 순번 스냅샷.
 *
 * @param rank 0-based 순번(ZRANK). 앞에 rank명이 이미 있다는 뜻.
 * @param totalWaiting 해당 드롭의 전체 대기 인원(ZCARD).
 * @param quantity 이 사용자가 요청한 수량(1인당 여러 개 구매 가능 - 사용자 수가 아니라
 *   수량으로 재고를 통제하기 위해 필요).
 */
data class WaitingTicket(
    val rank: Long,
    val totalWaiting: Long,
    val quantity: Int,
)
