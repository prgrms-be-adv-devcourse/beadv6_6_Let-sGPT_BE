-- 이미 발급된 입장권을 사용자 스스로 반납한다("포기" 선택). TTL 만료를 기다리는
-- sweep-admitted.lua의 즉시 실행판이고, 게이트웨이 release-outstanding.lua와 같은 키를 다룬다.
--
-- 왜 필요한가: remove-from-queue.lua는 대기열 쪽 키(순번/하트비트/수량/결정)만 지운다.
-- 그래서 이미 READY(입장권 보유)인 사용자가 GIVE_UP을 눌러도 admission 키가 남아
-- status-snapshot.lua가 계속 READY를 돌려줬고(첫 줄이 GET admission), outstanding도
-- 부풀린 채라 뒷사람 입장이 입장권 TTL(기본 180초)만큼 밀렸다.
--
-- KEYS[1]=admission:{dropId}:{userId} (STRING, 입장권 그 자체 - 값은 발급 수량)
-- KEYS[2]=admitted:{dropId}           (ZSET, member=userId, score=만료 epoch ms)
-- KEYS[3]=admitted:{dropId}:qty       (HASH, userId -> 발급 수량)
-- KEYS[4]=outstanding:{dropId}        (STRING, 정수)
--
-- ARGV[1]=userId
--
-- 반환: 실제로 outstanding에서 되돌린 수량(0 = 회수할 입장권이 없었음)

-- 1차 가드: 입장권 키를 실제로 지운 경우에만 계속한다.
--
-- DEL이 0이면 게이트웨이가 이미 GETDEL로 입장권을 소진했다는 뜻 = 주문이 진행 중이다.
-- 그 상태에서 여기서 outstanding을 깎으면, 곧이어 CREATED 이벤트를 받은
-- apply-created-reservation.lua가 또 깎아 이중 차감이 된다(release-admitted-tracking.lua
-- 주석에 기록된 "이중 공백" 레이스와 같은 종류의 사고 - outstanding이 실제보다 작아 보여
-- admit.lua가 필요 이상으로 사람을 들여보낸다). 그래서 아무 것도 하지 않고 빠진다.
--
-- GETDEL과 이 DEL은 둘 다 원자적이고 Redis는 단일 스레드라 정확히 한쪽만 성공한다:
--   이 스크립트가 이기면 -> 게이트웨이가 nil을 보고 주문을 거절(정상)
--   게이트웨이가 이기면 -> 여기서 0 반환, 기존 경로가 그대로 처리(정상)
-- 알려진 엣지(오류 아님): 게이트웨이가 이긴 뒤 그 주문이 5xx로 실패하면
-- restore-admission.lua가 입장권을 되살려, 포기했던 사용자가 다시 READY로 보일 수 있다.
-- 그래도 이중 차감보다는 낫기 때문에 이 가드를 유지한다.
if redis.call('DEL', KEYS[1]) == 0 then
  return 0
end

-- 2차 가드: outstanding 차감은 ZREM에 실제로 성공한 경우에만 한다
-- (sweep-admitted.lua / release-outstanding.lua와 동일한 이중 차감 방지 패턴 -
-- TTL 스위퍼가 한 발 먼저 회수했을 수 있다).
if redis.call('ZREM', KEYS[2], ARGV[1]) == 0 then
  return 0
end

local qty = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
redis.call('HDEL', KEYS[3], ARGV[1])
if qty > 0 then
  redis.call('DECRBY', KEYS[4], qty)
end

return qty
