-- 무응답 결정자 이탈 처리(단일 원자 실행): 맨 앞(rank 0) 사용자가 DECISION_REQUIRED를 받고도
-- (decision 해시에 'ASKED:<t>') 타임아웃이 지나도록 응답하지 않았으면 대기열에서 제거한다.
--
-- 왜 필수인가: 엄격한 FIFO에서 rank 0의 미결정은 큐 전체 정지다. 하트비트 스위퍼(sweep.lua)는
-- "폴링이 끊긴" 사람만 잡는데, 탭만 열어둔 무응답자는 폴링이 계속되므로 거기에 안 걸린다 -
-- 이 스크립트가 그 구멍을 닫는다. WAIT_CONFIRMED(명시적으로 기다리겠다고 답한 사람)는 제거
-- 대상이 아니다("먼저 온 사람이 자리를 지킨다" 정책의 정당한 행사).
--
-- 검사 대상이 rank 0 하나뿐인 이유: DECISION_REQUIRED 자체가 rank 0에게만 노출되고
-- (QueueService.resolveStatus + decide-partial.lua의 rank==0 이중 방어), 순번은 시간
-- score라 뒤로 밀리지 않으므로 ASKED 마커를 가진 사람은 항상 맨 앞이다.
--
-- 억울한 제거 방지(원자적 재확인): 타임아웃이 지났어도 그 사이 재고가 도착해 이 사람 몫이
-- 채워졌다면(available >= 요청수량) 제거하지 않고 ASKED 마커만 걷어낸다 - 다음 admit tick이
-- 정상 입장시킬 사람이다. 이 재확인이 앱 계층이 아니라 여기(원자 실행) 있어야 "확인과 제거
-- 사이에 재고가 도착하는" 레이스까지 닫힌다.
--
-- 버그 이력(MSA 경계 위반 제거, queue-remaining-sync 재설계 작업): 예전엔 KEYS[5]가 product
-- 소유 `drop:{dropId}` 해시를 직접 읽었다 - 다른 모듈의 내부 데이터스토어를 직접 침범하는
-- 것이라 없앴다. `remaining`은 이제 이 큐가 이미 갖고 있는 `total - reserved`로 계산한다
-- (수학적으로 product의 실제 remaining과 항상 같음이 증명됨).
--
-- KEYS[1]=queue:{dropId}            (ZSET)
-- KEYS[2]=queue:{dropId}:heartbeat  (ZSET)
-- KEYS[3]=queue:{dropId}:qty        (HASH)
-- KEYS[4]=decision:{dropId}         (HASH)
-- KEYS[5]=total:{dropId}            (STRING - 총재고, 불변값 캐시)
-- KEYS[6]=reserved:{dropId}         (STRING - 선점 누적 수량, CREATED/CANCELLED로 가감)
-- KEYS[7]=outstanding:{dropId}      (STRING)
--
-- ARGV[1]=now(epoch ms)  ARGV[2]=timeoutMs
--
-- 반환: 제거된 userId(문자열) - 아무도 제거하지 않았으면 ''

local front = redis.call('ZRANGE', KEYS[1], 0, 0)
if #front == 0 then
  return ''
end
local userId = front[1]

local decision = redis.call('HGET', KEYS[4], userId)
if decision == false or string.sub(decision, 1, 6) ~= 'ASKED:' then
  return ''
end
local askedAt = tonumber(string.sub(decision, 7))
if askedAt == nil or tonumber(ARGV[1]) < askedAt + tonumber(ARGV[2]) then
  return ''
end

local total = redis.call('GET', KEYS[5])
if total ~= false then
  local reserved = tonumber(redis.call('GET', KEYS[6]) or '0')
  local remaining = tonumber(total) - reserved
  if remaining < 0 then remaining = 0 end
  if remaining > tonumber(total) then remaining = tonumber(total) end
  local qty = tonumber(redis.call('HGET', KEYS[3], userId) or '1')
  local outstanding = tonumber(redis.call('GET', KEYS[7]) or '0')
  if remaining - outstanding >= qty then
    -- 그 사이 몫이 채워짐 - 결정 상태만 해소하고 남긴다(입장은 admit tick의 몫).
    redis.call('HDEL', KEYS[4], userId)
    return ''
  end
end

redis.call('ZREM', KEYS[1], userId)
redis.call('ZREM', KEYS[2], userId)
redis.call('HDEL', KEYS[3], userId)
redis.call('HDEL', KEYS[4], userId)
return userId
