package com.openat.queue.domain.repository

import com.openat.queue.domain.model.StockAdjustmentReason

/**
 * "선점된 재고" 누적 수량을 다룬다 - order가 주문을 생성(CREATED)하거나 취소/만료
 * (CANCELLED)할 때마다 발행하는 `StockAdjustment` Kafka 이벤트를 큐가 직접 구독해 가감한
 * 파생 데이터다. [ConfirmedSalesRepository]와 자매 관계지만 목적이 다르다:
 *
 * - [ConfirmedSalesRepository](확정) - "대기열 존속" 판정: 총재고 - 확정 = 0이면 매진.
 * - [ReservedStockRepository](선점, 이 인터페이스) - "구매자 입장 시 분기" 판정: 선점된
 *   재고 중 아직 확정 안 된 만큼(`reservedOf - confirmedOf`)이 재고 복구 가능성이 있는
 *   미확정 재고다 - 이 값으로 기다릴지/조정 구매할지/포기할지를 안내한다.
 *
 * 예전엔 이 값을 product의 `remaining`(직접 Redis 참조)에서 간접 계산했다
 * (`(총재고 - remaining) - confirmed`) - 이 값과 `reservedOf() - confirmed()`는 수학적으로
 * 항상 같아야 하는 값이지만(둘 다 "선점되고 아직 확정 안 된 수량"), 전자는 product 소유
 * Redis 키를 큐가 직접 읽는 MSA 경계 위반이라, 이제 큐가 구독하는 카프카 이벤트만으로
 * 자체 계산한다(remaining 직접 읽기는 admit.lua 등 원자성이 필요한 다른 경로에는 여전히
 * 남아있다 - 별도 후속 과제).
 */
interface ReservedStockRepository {

    /** 지금까지 선점(CREATED)된 뒤 아직 취소(CANCELLED)로 되돌아가지 않은 누적 수량. 이벤트가 아직 안 왔으면 0. */
    fun reservedOf(dropId: String): Long

    /**
     * `StockAdjustment` 이벤트를 소비해 선점 수량을 가감한다 - [StockAdjustmentReason.CANCELLED]면
     * `-count`([StockAdjustmentReason.CREATED]는 [applyCreatedReservationAndReleaseOutstanding]을
     * 대신 쓴다 - outstanding과 원자적으로 같이 처리해야 하기 때문). [eventId] 기준으로 멱등
     * 처리한다(Kafka at-least-once 재전달 대비). CREATED와 CANCELLED는 같은 주문이라도 서로
     * 다른 이벤트라 서로 다른 eventId를 갖는다는 전제(order 팀과 합의됨, ConfirmedSalesRepository의
     * COMPLETED/REFUNDED와 동일한 관례).
     */
    fun applyReservationAdjustment(dropId: String, eventId: String, count: Int, reason: StockAdjustmentReason)

    /**
     * CREATED 전용: `reserved += count`와 `outstanding -= count`를 하나의 원자 실행으로 같이
     * 처리한다 - apigateway가 성공 응답에서 더 이상 outstanding을 즉시 풀지 않고(대신
     * admitted 추적만 정리) 이 시점까지 "들고 있다가" 넘겨주는 핸드오프의 수신 측이다.
     *
     * 버그 이력(라이브 데모에서 재현): 예전엔 gateway가 즉시 outstanding을 풀고 이 메서드
     * (당시엔 [applyReservationAdjustment])가 reserved만 나중에 늘렸다 - 그 사이 밀리초
     * 단위 창에서 이미 소비된 재고가 양쪽 어디에도 안 잡혀 admit.lua가 과다 admission을
     * 일으켰다(apply-created-reservation.lua 헤더 주석 참고). 두 카운터를 원자적으로 같이
     * 옮기면 그 창이 사라진다.
     */
    fun applyCreatedReservationAndReleaseOutstanding(dropId: String, eventId: String, count: Int)
}
