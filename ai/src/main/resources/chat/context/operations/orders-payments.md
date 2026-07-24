# ORDER_PAYMENT — 주문·결제·환불·사가

주문 서비스는 주문 요청, 재고 확보와 결제의 사가 오케스트레이션, 취소, 보상을 담당한다. 주문 상태는 `PAYMENT_PENDING`, `COMPLETED`, `FAILED`, `CANCELLED`, `CANCEL_REQUESTED`, `REFUNDED`, `REFUND_FAILED`가 실제 전이에 쓰이며 `REFUND_PENDING`은 현재 전이 경로가 없는 예약 상태다.

주문 흐름:

1. 주문은 `PAYMENT_PENDING`으로 생성된다.
2. 상품 서비스의 동기 재고 차감이 성공해야 결제로 진행한다. 품절·미오픈·구매 한도 초과 등은 주문 실패 근거가 된다.
3. 결제 완료 결과를 받으면 주문을 `COMPLETED`로 확정하고 정산용 완료 이벤트를 발행한다.
4. 결제 실패나 결제 기한 만료 시 주문을 실패·취소 상태로 전이하고 확보한 재고의 복원을 시도한다.
5. 취소·환불 뒤 재고 복원이 끝나지 않으면 주문의 종결 상태만 보고 전체 보상이 완료됐다고 단정하지 않고 사가 상태를 함께 본다.

결제 서비스는 지갑과 PG 결제를 지원한다. 결제 상태는 `PENDING → PAYMENT_PENDING → APPROVED`가 정상 진행이며 이후 `PARTIALLY_REFUNDED` 또는 `REFUNDED`가 될 수 있다. `FAILED`, `CANCELED`, `REFUNDED`는 종결 상태다. 환불 건은 별도로 `PENDING`, `COMPLETE`, `FAILED`를 가진다.

PG 환불 응답이 불확실하거나 조회 결과가 `NOT_FOUND`이면 실제 환불이 성공했을 가능성을 배제할 수 없어 `PENDING`을 유지한다. 명시적 거절만 `FAILED`로 닫고 환불 가능 금액을 원복한다. 따라서 환불 `PENDING`을 곧바로 실패로 해석하거나 같은 환불을 다시 실행하라고 안내하면 안 된다.

사가 단계는 `ORDER_CREATED`, `STOCK_DECREASED`, `COMPLETED`, `COMPENSATING`, `COMPENSATION_COMPLETED`다. `COMPENSATING`은 복구가 진행 중이라는 뜻이다. 스케줄러는 취소·환불 후 재고 복원을 재시도하며 10분 이상 보상 중인 사가를 지연 경고 대상으로 기록한다.

개별 주문 한 건의 이력·사가 질문은 외부 주문번호가 정확히 주어진 경우에만 최소 스냅샷, 상태 전이 이벤트, 현재 사가를 읽을 수 있다. 최소 스냅샷에는 상품명·수량·주문 당시 단가·총액을 포함한다. 기간 내 주문 목록은 공개 주문번호와 이 비민감 상품·가격 필드만 최대 20행까지 제공할 수 있다. 내부 UUID, 회원·구매자·판매자 식별정보, 배송·연락처, 결제 키와 자유 실패 사유는 제공하지 않는다. 판단 순서는 현재 주문 상태 → 실패 코드·결제 만료 시각 → 시간순 상태 전이 → 현재 사가 단계다. 없는 이벤트나 원인을 만들어내지 않는다.

운영 점검 우선순위는 장시간 `PAYMENT_PENDING`, `FAILED` 증가, `CANCEL_REQUESTED`·`REFUND_FAILED`, `COMPENSATING` 정체, 결제·환불 이벤트 지연이다. 챗봇은 조회와 설명만 하며 취소, 환불, 재고 복원 재시도 같은 쓰기 API를 실행하지 않는다.
