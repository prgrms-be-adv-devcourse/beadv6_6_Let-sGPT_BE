package com.openat.queue.domain.model

import java.time.Instant

/**
 * 상태 폴링 한 번에 필요한 모든 값의 "그 순간의 일관된 스냅샷" - status-snapshot.lua가
 * 단일 원자 실행(1왕복)으로 만들어 준다. 판정(READY/WAITING/DECISION_REQUIRED/...)은
 * 이 값을 받아 애플리케이션 계층(QueueService.resolveStatus)이 수행한다.
 *
 * @param admittedQuantity 미소진 입장권 보유 시 그 수량(= READY). 있으면 나머지 필드는 무의미.
 * @param rank 대기 순번(0부터). null이면 대기열에 없음(NOT_IN_QUEUE).
 * @param remaining product 재고 캐시의 가용 재고. null이면 캐시 미워밍(안전하게 대기 유지).
 * @param total 드롭 총수량 캐시. null이면 캐시 miss - 필요 시 호출부가 product REST로 폴백.
 * @param decision 대화형 결정 상태. null이면 아직 물어본 적 없음.
 * @param reserved 선점(미확정) 누적 수량 - order의 CREATED/CANCELLED 이벤트로 큐가 직접
 *   집계한 값. `reserved - confirmed`가 "재고 복구 가능성이 있는 미확정 재고"다.
 */
data class QueueStatusSnapshot(
    val admittedQuantity: Int?,
    val rank: Long?,
    val totalWaiting: Long,
    val quantity: Int?,
    val remaining: Long?,
    val closeAt: Instant?,
    val outstanding: Long,
    val confirmed: Long,
    val total: Long?,
    val decision: DecisionState?,
    val reserved: Long,
)

/** 대화형 결정(DECISION_REQUIRED)에 대한 이 사용자의 상태 - `decision:{dropId}` 해시 값의 도메인 표현. */
sealed interface DecisionState {
    /**
     * "기다리겠다(WAIT)"를 명시적으로 선택함 - 무응답 타임아웃 제거 대상이 아니다(정책).
     *
     * @param grantableNowAtConfirm 확정 "그 순간"의 grantableNow(remaining-outstanding, 0
     *   이상). PARTIAL로 지금 당장 받을 수 있는 양이 그때 이후 바뀌었는지 비교하는 기준값.
     * @param maxAtConfirm 확정 "그 순간"의 optimisticMax(총재고-확정). null이면 확정 당시
     *   total이 아직 캐시되지 않아 값을 몰랐던 경우(보수적으로 취급).
     *
     * 버그 이력 1(라이브 데모에서 재현): 예전엔 이 값들이 없어서 "지금도 SHORTFALL
     * (optimisticMax < quantity)인가"만 보고 매 폴링마다 재질의했다 - 한 번 WAIT을 확정한
     * 사람이 상황이 전혀 안 변했는데도 폴링할 때마다(2초 간격) 계속 DECISION_REQUIRED로
     * 돌아와 "기다림을 눌러도 아무 효과 없이 같은 질문이 반복되는" 것처럼 보였다.
     *
     * 버그 이력 2(위 수정 직후, 역시 라이브 데모에서 재현): `maxAtConfirm`(optimisticMax)만
     * 저장하고 "그때보다 나빠졌을 때만" 재질의하도록 고쳤더니, 이번엔 반대 방향이 깨졌다 -
     * 다른 유저가 주문을 *취소*(CANCELLED)해서 재고가 회복돼도 대기자가 영영 못 깨어났다.
     * CANCELLED는 `reserved`를 줄여 grantableNow를 올리지만 `confirmed`는 안 건드리므로
     * optimisticMax는 전혀 안 바뀐다 - optimisticMax만 보는 게이트는 "취소로 PARTIAL이 새로
     * 가능해진" 신호 자체를 볼 수 없었다. 그래서 이제는 `grantableNowAtConfirm`도 같이
     * 기록해 - 확정 당시와 비교해 grantableNow/optimisticMax 둘 중 하나라도 바뀌면(나아졌든
     * 나빠졌든) 재질의한다 - QueueService.resolveStatus 참고.
     */
    data class WaitConfirmed(val grantableNowAtConfirm: Long, val maxAtConfirm: Long?) : DecisionState

    /** DECISION_REQUIRED를 노출했지만 아직 무응답 - [askedAtEpochMs] + timeout이 지나면 이탈 처리된다. */
    data class Asked(val askedAtEpochMs: Long) : DecisionState
}
