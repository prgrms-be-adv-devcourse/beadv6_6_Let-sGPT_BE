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
-- ARGV[1]=dropId  ARGV[2]=입장권 TTL(초)  ARGV[3]=now(epoch ms)
-- ARGV[4]=maxGhostScan(아래 "유령 정리" 방어용 상한 - 정상 흐름에서는 사실상 안 쓰임)
--
-- 반환: [userId1, qty1, userId2, qty2, ...] (입장 처리된 사람들)
--
-- 정책(엄격한 FIFO): 맨 앞(지금 차례인) 사람의 요청 수량이 그 시점 가용량보다 크면, 그
-- 자리에서 즉시 멈춘다 - 뒷사람에게 순서를 넘기지 않는다. "먼저 온 사람이 자리를 지킨다"는
-- 서비스 정책이라, 재고를 일시적으로 놀리는 한이 있어도 새치기를 허용하지 않는다. 그 사람이
-- WAIT/PARTIAL/GIVE_UP 중 하나로 응답하거나(QueueService.decide) 재고가 늘어 그 사람 몫이
-- 채워지기 전까지는, 뒤 순번은 이번 tick에서 아예 검토조차 되지 않는다.
-- (예전 버전은 반대로 "재고를 안 놀리려고" 앞사람을 건너뛰고 뒷사람을 먼저 입장시켰으나,
-- 이는 결과적으로 앞사람이 결정을 내리기도 전에 재고를 빼앗기는 것과 같아 정책과 맞지
-- 않았다. QueueService의 대화형 결정(DECISION_REQUIRED)이 실제로 뜻대로 동작하려면
-- "맨 앞 사람이 해결되기 전엔 아무도 못 들어간다"가 이 스크립트 레벨에서 보장돼야 한다.)
--
-- 방어(회계 정합성, 순서와 무관): 이미 미소진 입장권을 보유한 사용자(admitted ZSET에 이미
-- 존재)가 대기열에도 남아있으면(재진입 API 재호출 등으로 재등록된 비정상 상태) outstanding을
-- 중복 가산하지 않고 대기열에서만 조용히 제거한 뒤 다음 후보를 계속 본다 - 이건 "누구 차례인가"
-- 문제가 아니라 데이터 정합성 문제라서 위 엄격한 FIFO 정지 대상이 아니다. ARGV[4]는 바로 이
-- 유령 정리가 연달아 있을 때 무한정 스캔하지 않도록 막는 성능 보호용 상한이다(정상적인 대기자를
-- 만나면 그 즉시 위 정책에 따라 스캔이 끝나므로, 평소엔 이 상한에 도달할 일이 거의 없다).
--
-- 방어(회계 정합성): 애플리케이션 계층(QueueService.enter)에서도 같은 상황을 막지만, 그
-- 체크와 이 tick 실행 사이의 좁은 레이스까지 닫는 건 원자적으로 실행되는 이 스크립트뿐이다.

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
    -- 이미 미소진 입장권 보유(비정상 재등록) - 순서 방해가 아니므로 대기열에서만 제거하고
    -- 다음 후보를 계속 본다(엄격한 FIFO 정지 대상 아님).
    redis.call('ZREM', KEYS[1], userId)
    redis.call('ZREM', KEYS[2], userId)
    redis.call('HDEL', KEYS[3], userId)
    redis.call('HDEL', KEYS[8], userId)
    i = i + 1
  else
    local qty = tonumber(redis.call('HGET', KEYS[3], userId) or '1')

    if qty > available then
      break  -- 엄격한 FIFO: 지금 차례인 사람 몫이 안 되면 뒷사람은 보지 않고 그 자리에서 멈춘다
    end

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
    i = i + 1
  end
end

return admitted
