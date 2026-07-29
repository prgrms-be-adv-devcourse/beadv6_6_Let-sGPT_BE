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
-- KEYS[5]=giveup:{dropId}:{userId}    (STRING, GIVE_UP 의사 tombstone - 값 없음, TTL만 의미)
--
-- ARGV[1]=userId  ARGV[2]=tombstoneTtlSeconds
--
-- 반환: 실제로 outstanding에서 되돌린 수량(0 = 회수할 입장권이 없었음)

-- 1차 가드: 입장권 키를 실제로 지운 경우에만 계속한다.
--
-- DEL이 0이면 게이트웨이가 이미 GETDEL로 입장권을 소진했다는 뜻 = 주문이 진행 중이다.
-- 그 상태에서 여기서 outstanding을 깎으면, 곧이어 CREATED 이벤트를 받은
-- apply-created-reservation.lua가 또 깎아 이중 차감이 된다(release-admitted-tracking.lua
-- 주석에 기록된 "이중 공백" 레이스와 같은 종류의 사고 - outstanding이 실제보다 작아 보여
-- admit.lua가 필요 이상으로 사람을 들여보낸다). 그래서 outstanding/admitted는 건드리지 않고
-- 대신 tombstone만 남긴 뒤 빠진다.
--
-- GETDEL과 이 DEL은 둘 다 원자적이고 Redis는 단일 스레드라 정확히 한쪽만 성공한다:
--   이 스크립트가 이기면 -> 게이트웨이가 nil을 보고 주문을 거절(정상)
--   게이트웨이가 이기면 -> 여기서 0 반환, tombstone만 남기고 기존 경로가 그대로 처리
--
-- 버그 이력(발견: 초기 구현은 여기서 아무 것도 안 하고 끝났다): 게이트웨이가 이긴 뒤 그
-- 주문이 5xx로 실패하면 apigateway의 restore-admission.lua가 admitted ZSET에 남아있는지만
-- 보고 입장권을 되살렸다 - GIVE_UP 의사가 어디에도 기록되지 않아서, 포기했던 사용자가 다시
-- READY로 보이는 lost-update였다. tombstone을 남겨 restore-admission.lua가 복원 직전
-- 이 키를 확인하고 건너뛰게 한다(그쪽 스크립트 수정과 짝). TTL은 게이트웨이 다운스트림
-- 응답 타임아웃보다 넉넉히 잡아야 그 안의 모든 5xx를 커버한다 - QueueProperties.Admission
-- .giveUpTombstoneTtlSeconds 참고. TTL이 지나 사라지면 늦게 도착한 restore가 다시 성공할
-- 수 있지만(안전 측 실패), 그 시점엔 이미 사용자가 재진입해야 하는 상황일 가능성이 높다.
if redis.call('DEL', KEYS[1]) == 0 then
  redis.call('SET', KEYS[5], '1', 'EX', ARGV[2])
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
