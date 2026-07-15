-- 재고 인지형 입장 처리(단일 원자 실행): product의 실재고(remaining)에서 이 큐가 이미 발급한
-- 미소진 입장권 수량(outstanding)을 뺀 만큼만, 대기열 앞에서부터 순서대로 입장시킨다.
-- "사용자 수"가 아니라 "요청 수량"으로 재고를 통제한다(1인당 여러 개 구매 가능).
--
-- KEYS[1]=queue:{dropId}            (ZSET, 대기 순번)
-- KEYS[2]=queue:{dropId}:heartbeat  (ZSET)
-- KEYS[3]=queue:{dropId}:qty        (HASH, userId -> 요청수량)
-- KEYS[4]=drop:{dropId}             (HASH, product 소유 - remaining/openAt/closeAt, 읽기 전용)
-- KEYS[5]=outstanding:{dropId}      (STRING, 미소진 입장권 수량 합)
-- KEYS[6]=admitted:{dropId}         (ZSET, member=userId, score=입장권 만료 epoch ms)
-- KEYS[7]=admitted:{dropId}:qty     (HASH, userId -> 발급 수량)
-- KEYS[8]=decision:{dropId}         (HASH, Phase B 대화형 결정 상태 - 정상 입장 시 함께 정리)
--
-- ARGV[1]=dropId  ARGV[2]=입장권 TTL(초)  ARGV[3]=now(epoch ms)  ARGV[4]=maxScan(이번 tick에 살펴볼 대기자 상한)
--
-- 반환: [userId1, qty1, userId2, qty2, ...] (입장 처리된 사람들)
--
-- 주의(Phase A 한계, 문서화된 트레이드오프): 맨 앞 사람의 요청 수량이 그 시점 가용량보다 크면
-- 건너뛰고 다음 후보를 계속 살펴본다(뒤쪽의 작은 수량 요청자가 먼저 들어갈 수 있어 엄격한 FIFO가
-- 깨진다 - 무한정 밀릴 수 있다는 뜻. 가용 재고를 놀리지 않기 위한 의도적 선택이다). 앞사람에게
-- "기다림/부분구매"를 묻는 대화형 결정은 Phase B에서 다룬다.
--
-- 방어(회계 정합성): 이미 미소진 입장권을 보유한 사용자(admitted ZSET에 이미 존재)가 대기열에도
-- 남아있으면(재진입 API 재호출 등으로 재등록된 비정상 상태) outstanding을 중복 가산하지 않고
-- 대기열에서만 조용히 제거한다. 애플리케이션 계층(QueueService.enter)에서도 같은 상황을 막지만,
-- 그 체크와 이 tick 실행 사이의 좁은 레이스까지 닫는 건 원자적으로 실행되는 이 스크립트뿐이다.

local now = tonumber(ARGV[3])

local closeAt = tonumber(redis.call('HGET', KEYS[4], 'closeAt') or '-1')
if closeAt >= 0 and now >= closeAt then
  return {}
end

local remainingRaw = redis.call('HGET', KEYS[4], 'remaining')
if remainingRaw == false then
  -- 캐시 미존재(워밍 전) - 안전하게 이번 tick은 건너뛴다.
  return {}
end
local remaining = tonumber(remainingRaw)

local outstanding = tonumber(redis.call('GET', KEYS[5]) or '0')
local available = remaining - outstanding
if available <= 0 then
  return {}
end

local maxScan = tonumber(ARGV[4])
local candidates = redis.call('ZRANGE', KEYS[1], 0, maxScan - 1)
if #candidates == 0 then
  return {}
end

local ttl = tonumber(ARGV[2])
local expireAt = now + (ttl * 1000)
local admitted = {}

local i = 1
while i <= #candidates do
  local userId = candidates[i]

  if redis.call('ZSCORE', KEYS[6], userId) then
    -- 이미 미소진 입장권 보유 - outstanding 중복 가산 없이 대기열에서만 제거.
    redis.call('ZREM', KEYS[1], userId)
    redis.call('ZREM', KEYS[2], userId)
    redis.call('HDEL', KEYS[3], userId)
    redis.call('HDEL', KEYS[8], userId)
  else
    local qty = tonumber(redis.call('HGET', KEYS[3], userId) or '1')

    if qty <= available then
      redis.call('ZREM', KEYS[1], userId)
      redis.call('ZREM', KEYS[2], userId)
      redis.call('HDEL', KEYS[3], userId)
      redis.call('HDEL', KEYS[8], userId)

      redis.call('SET', 'admission:' .. ARGV[1] .. ':' .. userId, qty, 'EX', ttl)
      redis.call('ZADD', KEYS[6], expireAt, userId)
      redis.call('HSET', KEYS[7], userId, qty)
      redis.call('INCRBY', KEYS[5], qty)

      available = available - qty
      table.insert(admitted, userId)
      table.insert(admitted, tostring(qty))

      if available <= 0 then
        break
      end
    end
  end

  i = i + 1
end

return admitted
