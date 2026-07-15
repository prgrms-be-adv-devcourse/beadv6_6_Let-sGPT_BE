-- 주문 요청이 끝났을 때(성공/실패/취소 무관) 미소진 입장권 추적에서 이 사용자를 제거하고
-- outstanding을 되돌린다. queue 모듈의 sweep-admitted.lua와 같은 키를 다루는 짝 스크립트다.
--
-- KEYS[1]=admitted:{dropId}      (ZSET, member=userId)
-- KEYS[2]=admitted:{dropId}:qty  (HASH, userId -> 발급 수량)
-- KEYS[3]=outstanding:{dropId}   (STRING, 정수)
-- ARGV[1]=userId  ARGV[2]=qty(게이트웨이가 이미 admission 키에서 읽은 값 - HASH 조회 실패 시 폴백)
--
-- 반환: 실제로 해제한 수량(0이면 이미 다른 경로(TTL 스위퍼)가 먼저 회수한 것 - 멱등)
--
-- 멱등성: queue 모듈의 AdmittedTicketSweeper가 TTL 만료로 같은 사용자를 먼저 회수할 수 있다.
-- ZREM의 반환값(실제로 제거했는지)으로만 outstanding을 차감해 이중 차감을 막는다.

local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if removed == 1 then
  local qty = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or ARGV[2])
  redis.call('HDEL', KEYS[2], ARGV[1])
  redis.call('DECRBY', KEYS[3], qty)
  return qty
end
return 0
