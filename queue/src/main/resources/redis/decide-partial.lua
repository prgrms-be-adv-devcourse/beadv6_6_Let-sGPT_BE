-- "부분구매" 결정의 원자적 실행: 지정된 사용자 한 명을 그 순간의 가용 재고
-- (remaining - outstanding)만큼만 즉시 입장시킨다. 폴링 시점에 보여준 grantableNow는
-- 참고값일 뿐이고, 실제 발급량은 이 스크립트 실행 시점에 다시 계산한다(그 사이 다른 사람이
-- admit되거나 재고가 줄었을 수 있으므로 반드시 재확인).
--
-- 공정성 방어(필수): 엄격한 FIFO 정책상 지금 맨 앞(rank 0)이 아닌 사용자는 절대 여기서
-- 입장시키지 않는다. admit.lua도 맨 앞사람이 안 풀리면 뒷사람은 아예 보지도 않고 그 자리에서
-- 멈추므로(admit.lua 헤더 주석 참고), rank>0인 사용자에게 PARTIAL을 허용해버리면 아직 자기
-- 차례도 안 된 사람이 앞사람을 제치고 그 순간 가용 재고를 가로채는 셈이 된다 - "순번대로,
-- 못 든 사람은 대기"라는 대전제를 깨는 결함이다. 애플리케이션 계층(QueueService.resolveStatus)도
-- 같은 조건(rank>0)이면 DECISION_REQUIRED 자체를 안 보여주지만, 그 체크와 이 스크립트 실행
-- 사이의 레이스(막 앞사람이 빠져나가 방금 rank 0이 된 순간 등)까지 막는 건 원자적으로 실행되는
-- 이 스크립트뿐이다.
--
-- KEYS[1]=queue:{dropId}            (ZSET)
-- KEYS[2]=queue:{dropId}:heartbeat  (ZSET)
-- KEYS[3]=queue:{dropId}:qty        (HASH)
-- KEYS[4]=drop:{dropId}             (HASH, product 소유 - remaining, 읽기 전용)
-- KEYS[5]=outstanding:{dropId}      (STRING)
-- KEYS[6]=admitted:{dropId}         (ZSET)
-- KEYS[7]=admitted:{dropId}:qty     (HASH)
-- KEYS[8]=decision:{dropId}         (HASH)
--
-- ARGV[1]=dropId  ARGV[2]=userId  ARGV[3]=입장권 TTL(초)  ARGV[4]=now(epoch ms)
--
-- 반환: 실제 발급 수량(정수, > 0) - 0이면 그 순간 가용 재고가 없거나(또는 맨 앞이 아니라)
-- 아무 것도 안 함.

local userId = ARGV[2]

local rank = redis.call('ZRANK', KEYS[1], userId)
if rank == false or rank ~= 0 then
  return 0
end

local requestedQty = tonumber(redis.call('HGET', KEYS[3], userId) or '0')
if requestedQty <= 0 then
  return 0
end

local remainingRaw = redis.call('HGET', KEYS[4], 'remaining')
if remainingRaw == false then
  return 0
end
local remaining = tonumber(remainingRaw)
local outstanding = tonumber(redis.call('GET', KEYS[5]) or '0')
local available = remaining - outstanding
if available <= 0 then
  return 0
end

local grant = requestedQty
if grant > available then
  grant = available
end

local ttl = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local expireAt = now + (ttl * 1000)

redis.call('ZREM', KEYS[1], userId)
redis.call('ZREM', KEYS[2], userId)
redis.call('HDEL', KEYS[3], userId)
redis.call('HDEL', KEYS[8], userId)

redis.call('SET', 'admission:' .. ARGV[1] .. ':' .. userId, grant, 'EX', ttl)
redis.call('ZADD', KEYS[6], expireAt, userId)
redis.call('HSET', KEYS[7], userId, grant)
redis.call('INCRBY', KEYS[5], grant)

return grant
