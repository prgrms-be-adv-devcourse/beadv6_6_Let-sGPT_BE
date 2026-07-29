package com.openat.queue.infrastructure.persistence

/**
 * 대기열 Redis 키 네임스페이스. 물리적으로 분리된 게 아니라 dropId를 키에 포함시켜
 * 논리적으로 격리한다 - 드롭 A가 몰려도 `queue:A`에만 쌓이고 `queue:B`는 독립적으로 동작한다.
 */
object RedisKeys {
    fun queue(dropId: String): String = "queue:$dropId"

    fun heartbeat(dropId: String): String = "queue:$dropId:heartbeat"

    /** 대기 중인 사용자의 요청 수량. member=userId, field=userId, value=quantity (HASH). */
    fun waitingQuantity(dropId: String): String = "queue:$dropId:qty"

    /** 입장권(admission ticket). 클라이언트가 들고 다니는 토큰이 아니라 서버 측 키 자체가 티켓이다.
     * 값은 발급 수량(quantity) - 게이트웨이가 GETDEL로 읽어 outstanding 해제 시 사용한다. */
    fun admission(dropId: String, userId: String): String = "admission:$dropId:$userId"

    /** 입장은 했지만 아직 주문으로 소진되지 않은 티켓 - member=userId, score=만료 epoch millis.
     * TTL을 넘기면 AdmittedTicketSweeper가 회수해 outstanding을 되돌린다. */
    fun admitted(dropId: String): String = "admitted:$dropId"

    /** admitted 멤버별 발급 수량 (HASH, field=userId, value=quantity) - 스위퍼가 outstanding 차감량을 알기 위함. */
    fun admittedQuantity(dropId: String): String = "admitted:$dropId:qty"

    /** 미소진 입장권 수량 합계 (STRING, 정수). Lua로만 원자 증감 - "지금 당장 더 들여보낼 수 있는 양"을
     * product의 remaining에서 빼는 데 쓴다: available = remaining - outstanding. */
    fun outstanding(dropId: String): String = "outstanding:$dropId"

    /** queue 소유 드롭 메타데이터 캐시 - product의 `GET /api/v1/drops/{dropId}` 응답을 캐시 미스 시
     * 1회 호출해 채운다(product의 `drop:{dropId}` Redis 해시를 직접 읽던 예전 방식 폐기 - MSA 경계
     * 위반이었음. queue-remaining-sync 재설계 작업 참고). HASH, field "closeAt"(epoch ms 문자열,
     * 없으면 "-1"), field "limitPerUser"(정수 문자열, 없으면 "-1"). `remaining`은 이 해시에 없다 -
     * `total(dropId) - reserved(dropId)`로 계산한다(핵심 발견: 수학적으로 항상 같은 값이라
     * product Redis를 직접 안 읽어도 된다). */
    fun dropMeta(dropId: String): String = "drop-meta:$dropId"

    /** 대화형 결정(Phase B) 상태 - HASH, field=userId, value="WAIT_CONFIRMED"(있으면 이미 "기다림"을
     * 선택한 것 - 같은 질문을 반복해서 묻지 않기 위한 표시). 없으면 아직 안 물어봤거나 답 안 함. */
    fun decision(dropId: String): String = "decision:$dropId"

    /** 확정(결제완료) 누적 수량 - queue 자신이 StockAdjustment 이벤트(COMPLETED/REFUNDED)를
     * 소비해 가감하는 큐 소유 값(product/order의 값을 그대로 옮겨 쓰는 게 아니라 "몇 개나 진짜로
     * 안 돌아오는지"를 큐가 스스로 집계한 파생 데이터). 환불로 다시 줄어들 수 있어 단조증가가
     * 아니다. STRING, 정수. */
    fun confirmed(dropId: String): String = "confirmed:$dropId"

    /** 확정 수량에 이미 반영한 eventId 집합(SET) - Kafka의 at-least-once 재전달로 같은 이벤트가
     * 두 번 와도 중복 반영하지 않기 위한 멱등 처리용(COMPLETED/REFUNDED는 서로 다른 eventId). */
    fun confirmedSeen(dropId: String): String = "confirmed:$dropId:seen"

    /** 선점(미확정) 누적 수량 - queue 자신이 StockAdjustment 이벤트(CREATED/CANCELLED)를
     * 소비해 가감하는 큐 소유 값. "선점된 재고 중 아직 확정 안 된 만큼"(reserved-confirmed)이
     * 재고 복구 가능성이 있는 미확정 재고다(구매자 입장 시 기다림/조정구매/포기 분기 판정에
     * 쓰임). CANCELLED로 다시 줄어들 수 있어 단조증가가 아니다. STRING, 정수. */
    fun reserved(dropId: String): String = "reserved:$dropId"

    /** 선점 수량에 이미 반영한 eventId 집합(SET) - confirmedSeen과 동일한 목적의 멱등 처리용
     * (CREATED/CANCELLED는 서로 다른 eventId). */
    fun reservedSeen(dropId: String): String = "reserved:$dropId:seen"

    /** 드롭의 총 수량(불변값) - product의 `GET /drops/{dropId}` 응답을 최초 1회 호출해 캐싱한 것.
     * STRING, 정수. */
    fun total(dropId: String): String = "total:$dropId"

    /** 정적 hot-drops 목록을 대체하는 동적 발견 레지스트리(SET, member=dropId). 대기자가 생기거나
     * (enqueue) 즉시 입장권이 발급되는(fast-admit) 순간 등록되고, 대기열/미소진 입장권이 모두
     * 비면 [queue.infrastructure.schedule.ExpiredWaiterSweeper]가 제거한다. 스케줄러/스위퍼가
     * "어떤 dropId를 살펴봐야 하는지" 이 SET 하나로 알아낸다 - dropId마다 물리적으로 분리된
     * 키가 아니라 전역 키 하나뿐이다. */
    fun activeDrops(): String = "queue:active-drops"

    /** enqueue 시점의 원 요청 W3C traceparent 보관소 (HASH, field=userId, value=traceparent).
     * enqueue와 admit이 서로 다른 실행 문맥/tick에서 일어나 Redis 외에는 트레이스 문맥을 실어
     * 나를 수단이 없다. admit 스팬에서 원 enqueue 트레이스로 링크를 걸기 위한 부가 데이터일 뿐
     * 입장 판정 원자성과 무관하므로 Lua 밖에서 다룬다. admit 시 해당 필드를 삭제하고, 유령
     * 데이터 방지를 위해 해시 자체에 TTL을 건다. */
    fun enqueueTrace(dropId: String): String = "queue:$dropId:traceparent"

    /** SSE 푸시용 Redis Pub/Sub 채널(WebFlux+SSE 전환분). 이 dropId의 대기열 상태가 바뀔 수
     * 있는 지점(입장 배치 admit, decision, sweep)에서 [QueueEventPublisher]가 "변경됨" 신호만
     * 싣는다 - payload에 누구의 무엇이 바뀌었는지는 안 담는다. 구독 중인 각 SSE 커넥션이 신호를
     * 받으면 자기 자신의 상태를 다시 조회해(status-snapshot.lua) 직전 값과 다를 때만 클라이언트로
     * 내려보낸다(QueueStreamService 참고) - 폴링처럼 "누가 요청했으니 계산한다"가 아니라
     * "바뀌었으니 계산한다"로 뒤집힌 것이 이 전환의 핵심이다. */
    fun eventsChannel(dropId: String): String = "queue-events:$dropId"

    /** GIVE_UP 의사 표시(tombstone) - STRING, 값 없음("1"), TTL만 의미 있음. `release-admission
     * .lua`가 게이트웨이의 GETDEL에 진 경우(이미 주문 진행 중) 이 키를 남겨, 그 주문이 나중에
     * 실패해 apigateway의 `restore-admission.lua`가 입장권을 되살리려 할 때 여기 tombstone이
     * 있으면 복원을 건너뛰게 한다. TTL은 게이트웨이 다운스트림 응답 타임아웃(현재 10분,
     * apigateway response-timeout)보다 넉넉히 잡아야 한다 - 그 안에 5xx 응답이 오는 모든
     * 케이스를 커버해야 하기 때문. GIVE_UP은 항상 이 키를 SET한 뒤 값을 넘겨주므로 TTL이
     * 지나 사라져도 안전 측 실패(늦게 도착한 restore가 다시 성공)일 뿐 사용자는 재진입하면 된다. */
    fun giveUpTombstone(dropId: String, userId: String): String = "giveup:$dropId:$userId"
}
