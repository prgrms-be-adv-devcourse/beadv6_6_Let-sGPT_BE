-- 즉시 입장 시도 후 실패 시 대기열 등록(단일 원자 실행). enqueue.lua를 대체한다.
--
-- 정적 hot-drops 목록이 없는 세계에서, 경쟁이 전혀 없는 드롭까지 admit.lua의 스케줄러 tick을
-- 기다리게 하면(최대 admission.interval-ms) 평범한 주문마다 불필요한 지연이 생긴다. 그래서
-- "대기 중인 사람이 아무도 없고(ZCARD==0) 요청 수량만큼 재고가 즉시 가용하면, 대기열에 넣지
-- 않고 그 자리에서 입장권을 발급"한다 - 대기열이 비어 있다는 사실 자체가 "새치기가 없다"는
-- 공정성 보장이므로 안전하다. 그 조건에 못 미치면(이미 누가 대기 중이거나 가용 재고 부족)
-- 평범하게 대기열에 등록해 다음 admit.lua tick(또는 대화형 결정)을 기다리게 한다.
--
-- KEYS[1]=queue:{dropId}            (ZSET, 대기 순번)
-- KEYS[2]=queue:{dropId}:heartbeat  (ZSET)
-- KEYS[3]=queue:{dropId}:qty        (HASH, userId -> 요청수량)
-- KEYS[4]=drop:{dropId}             (HASH, product 소유 - remaining, 읽기 전용)
-- KEYS[5]=outstanding:{dropId}      (STRING, 미소진 입장권 수량 합)
-- KEYS[6]=admitted:{dropId}         (ZSET, member=userId, score=입장권 만료 epoch ms)
-- KEYS[7]=admitted:{dropId}:qty     (HASH, userId -> 발급 수량)
-- KEYS[8]=queue:active-drops        (SET, member=dropId - 동적 발견 레지스트리)
--
-- ARGV[1]=dropId  ARGV[2]=userId  ARGV[3]=quantity  ARGV[4]=now(epoch ms)  ARGV[5]=입장권 TTL(초)
--
-- 반환: {admitted(1|0), quantity} - admitted=1이면 즉시 입장권 발급됨(quantity=발급 수량),
--   0이면 대기열에 등록됨(quantity=0, 이후 상태는 status 폴링으로 확인).

local dropId, userId = ARGV[1], ARGV[2]
local quantity = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

-- 어느 브랜치로 가든 이 dropId는 이제 스케줄러/스위퍼가 살펴봐야 할 대상이다(대기 중이거나
-- 미소진 입장권을 들고 있으므로).
redis.call('SADD', KEYS[8], dropId)

if redis.call('ZCARD', KEYS[1]) == 0 then
  local remainingRaw = redis.call('HGET', KEYS[4], 'remaining')
  if remainingRaw ~= false then
    local remaining = tonumber(remainingRaw)
    local outstanding = tonumber(redis.call('GET', KEYS[5]) or '0')
    local available = remaining - outstanding
    if available >= quantity then
      local expireAt = now + (ttl * 1000)
      redis.call('SET', 'admission:' .. dropId .. ':' .. userId, quantity, 'EX', ttl)
      redis.call('ZADD', KEYS[6], expireAt, userId)
      redis.call('HSET', KEYS[7], userId, quantity)
      redis.call('INCRBY', KEYS[5], quantity)
      -- admit.lua와 동일한 이유로 반환값을 전부 문자열화한다: StringRedisTemplate 쪽
      -- RedisScript<List<String>>는 멀티불크 응답 원소가 전부 문자열이어야 한다 - 숫자를
      -- 그대로 반환하면 Lettuce가 Long으로 역직렬화해 List<String> 캐스팅이 실패한다.
      return {'1', tostring(quantity)}
    end
  end
  -- remaining 캐시 미존재(워밍 전) 또는 가용 재고 부족 - 아래로 흘러 평범하게 대기열 등록.
end

redis.call('ZADD', KEYS[1], 'NX', now, userId)
redis.call('ZADD', KEYS[2], now, userId)
redis.call('HSET', KEYS[3], userId, quantity)
return {'0', '0'}
