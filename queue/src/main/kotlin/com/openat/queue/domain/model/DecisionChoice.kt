package com.openat.queue.domain.model

/**
 * `DECISION_REQUIRED` 상태에 대한 사용자의 응답.
 *
 * - [WAIT]: 요청 수량을 다 채울 때까지 계속 대기한다(첫 번째 질문 - "지금 가능한 만큼만 살지,
 *   기다릴지"에 대한 응답). 이후 낙관적 최대(optimisticMax)가 요청 수량 밑으로 떨어지면
 *   [DecisionChoice]를 다시 물어야 한다(이때는 WAIT 선택지 자체가 없다 - 이미 불가능해졌으므로).
 * - [PARTIAL]: 그 순간 가용한 수량(`grantableNow`)만큼만 즉시 입장 처리한다 - 원래 요청보다
 *   적어도 지금 확보한다는 뜻. 선택 즉시 서버가 원자적으로 재확인 후 입장권을 발급한다
 *   (폴링 시점의 grantableNow는 참고값일 뿐, 실제 발급 수량은 결정 시점에 다시 계산한다).
 *   나머지(원래 요청 - 실제 발급분)에 대해 우선순위를 주지 않는다 - 더 필요하면 새로 `/entry`.
 * - [GIVE_UP]: 대기열에서 이탈한다. 재고를 아예 포기.
 */
enum class DecisionChoice {
    WAIT,
    PARTIAL,
    GIVE_UP,
}
