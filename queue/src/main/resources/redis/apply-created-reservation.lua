-- order의 StockAdjustment(CREATED) 이벤트를 reserved 카운터에 반영하면서, 같은 원자 실행
-- 안에서 outstanding도 함께 차감한다 - "선점 반영"과 "입장권 회수"를 하나로 묶는 핸드오프다.
--
-- 버그 이력(라이브 데모 10명×2개/재고5 동시 테스트에서 재현, "이중 공백" 레이스): 예전엔
-- apigateway(AdmissionCheckGatewayFilterFactory)가 주문 생성 응답이 오는 즉시(동기)
-- outstanding을 풀었다. 그런데 reserved 증가는 이 스크립트(=StockAdjustmentConsumer가
-- CREATED를 실제로 컨슘하는 시점)에만 일어나므로 - order DB 커밋 → AFTER_COMMIT 리스너 →
-- Kafka producer.send(네트워크) → 이 컨슈머의 poll(네트워크+컨슈머그룹 처리) - 물리적으로
-- 0이 아닌 시간이 걸린다. "outstanding은 이미 풀렸는데 reserved는 아직 안 늘어난" 밀리초
-- 단위 창이 항상 존재했고, 그 창 안에서는 이미 소비된 재고가 양쪽 어디에도 안 잡혀
-- admit.lua가 실제보다 더 많은 사람을 들여보냈다(3번째 유저까지 READY를 받았다가 실제
-- 주문 시점에 product의 실시간 재검증으로 SOLD_OUT 거절 - 진짜 오버셀은 아니지만 "티켓
-- 받고 거절" UX 손실).
--
-- 고침: apigateway는 이제 성공(2xx) 응답에서 outstanding을 풀지 않고(admitted 추적만
-- 정리, release-outstanding.lua 대신 release-admitted-tracking.lua 사용), 이 스크립트가
-- reserved 증가와 outstanding 감소를 원자적으로 같이 처리한다 - 두 카운터가 항상 함께
-- 움직이므로 그 창 자체가 없어진다(어느 쪽이 먼저 랜딩하든 결과가 같다).
--
-- 이중 차감 방지: apigateway가 admitted:{dropId} ZSET에서 해당 사용자를 이미 제거했으므로
-- (성공 응답 시점, 사용자 단위), 이 스크립트가 나중에 outstanding을 깎아도 TTL 스위퍼
-- (sweep-admitted.lua)가 같은 사용자를 다시 찾아 중복 차감할 일이 없다(그 스위퍼는 여전히
-- admitted ZSET에 남아있는 - 즉 진짜로 방치된 - 티켓만 대상으로 하므로).
--
-- 새로운 실패 모드(허용됨, 문서화): Kafka *발행* 자체가 영구 실패하면(브로커 다운 등, 이건
-- consumer 쪽 DLQ가 못 잡는 producer 쪽 실패) 이 스크립트가 영영 안 불려 outstanding이
-- 영구히 묶인다(과소 admission 방향 leak). 예전 방식(과다 admission 방향 leak)보다 안전한
-- 방향이라 감내한다 - 근본 해법은 발행 신뢰성 자체를 높이는 것(범위 밖, 별도 과제).
--
-- KEYS[1]=reserved:{dropId}:seen  (SET, 멱등 처리 - apply-stock-adjustment.lua와 같은 키)
-- KEYS[2]=reserved:{dropId}       (STRING, 선점 누적 수량)
-- KEYS[3]=outstanding:{dropId}    (STRING, 미소진 입장권 수량 합)
--
-- ARGV[1]=eventId  ARGV[2]=count(양수 - CREATED는 항상 +count)
--
-- 반환: 1=이번에 반영됨, 0=이미 반영된 eventId라 무시(재전달 멱등 처리)

if redis.call('SADD', KEYS[1], ARGV[1]) == 1 then
  redis.call('INCRBY', KEYS[2], ARGV[2])
  redis.call('DECRBY', KEYS[3], ARGV[2])
  return 1
end
return 0
