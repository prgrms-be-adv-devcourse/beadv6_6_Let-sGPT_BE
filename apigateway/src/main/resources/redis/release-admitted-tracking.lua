-- 주문이 실제로 성공(2xx)했을 때만 쓰는 "부분" 해제 - admitted 추적(ZSET/HASH)만 정리하고
-- outstanding은 일부러 건드리지 않는다. release-outstanding.lua의 자매 스크립트다.
--
-- 버그 이력(라이브 데모 10명×2개/재고5 동시 테스트에서 재현, "이중 공백" 레이스): 예전엔
-- 이 자리에서 release-outstanding.lua를 그대로 써서 outstanding까지 즉시 풀었다. 그런데
-- queue 쪽 reserved 증가는 order의 CREATED Kafka 이벤트가 실제로 컨슘돼야만 일어나므로
-- (물리적으로 0이 아닌 지연), "outstanding은 이미 풀렸는데 reserved는 아직 안 늘어난"
-- 밀리초 단위 창이 생겨 그 사이 admit.lua가 실제보다 더 많은 사람을 들여보냈다(3번째
-- 유저까지 READY를 받았다가 실제 주문 시점에 SOLD_OUT 거절 - 진짜 오버셀은 아니지만
-- "티켓 받고 거절"이라는 UX 손실). 이제 성공 응답에서는 outstanding을 여기서 안 풀고,
-- queue의 StockAdjustmentConsumer가 CREATED를 컨슘하는 바로 그 순간 reserved 증가와
-- 함께 원자적으로 넘겨받는다(apply-created-reservation.lua) - 두 카운터가 항상 같이
-- 움직이므로 그 창이 사라진다.
--
-- admitted 추적은 그와 별개로 여기서 즉시 정리해야 한다(사용자 단위라 queue의 Kafka
-- 컨슈머는 dropId+count만 알고 어떤 유저인지 모른다 - StockAdjustment 이벤트 계약에
-- userId가 없다, order/payment 팀과 합의된 계약이라 이번 수정 범위에서 바꾸지 않았다):
-- 정리하지 않으면 admit.lua의 재등록 중복 방지(ZSCORE 체크)에 이 사용자가 계속 "아직
-- 입장권 보유 중"으로 잘못 보이고, TTL 스위퍼(sweep-admitted.lua)도 나중에 이 사용자를
-- 다시 찾아 outstanding을 또 깎으려 들 수 있다(이중 차감). 이 스크립트가 즉시 ZREM하므로
-- 스위퍼는 이 사용자를 더 이상 찾지 못한다 - 정확히 한 번만(컨슈머가) outstanding을 깎는다.
--
-- KEYS[1]=admitted:{dropId}      (ZSET, member=userId)
-- KEYS[2]=admitted:{dropId}:qty  (HASH, userId -> 발급 수량)
-- ARGV[1]=userId
--
-- 반환: 1=정리됨, 0=이미 다른 경로(TTL 스위퍼)가 먼저 회수함(멱등)

local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if removed == 1 then
  redis.call('HDEL', KEYS[2], ARGV[1])
end
return removed
