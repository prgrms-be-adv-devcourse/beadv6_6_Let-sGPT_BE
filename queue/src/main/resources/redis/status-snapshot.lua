-- 상태 폴링(hot path)의 모든 읽기 + 하트비트 갱신을 단일 원자 실행(1왕복)으로 묶는다.
--
-- 예전에는 resolveStatus 한 번에 admittedQuantityOf(GET) → ticketOf(ZRANK+ZCARD+HGET) →
-- touchHeartbeat(ZADD) → 재고 스냅샷(HMGET, 그것도 두 번) → outstanding(GET) →
-- confirmed/total(GET×2)로 최대 9번의 순차 왕복이 발생했다 - 대기 인원 × 폴링 빈도만큼
-- Redis와 서버 스레드풀을 태우는 구조라, 읽기 전부를 이 스크립트 하나로 통합했다.
-- 판정 로직은 애플리케이션 계층(QueueService.resolveStatus)에 남긴다 - 이 스크립트는
-- "그 순간의 일관된 스냅샷"만 제공한다(원자 실행이라 ZRANK와 HGET 사이에 값이 바뀌는
-- 찢어진 읽기(torn read)도 함께 사라진다).
--
-- 버그 이력(MSA 경계 위반 제거, queue-remaining-sync 재설계 작업): 예전엔 KEYS[5]가
-- product 소유 `drop:{dropId}` 해시를 직접 읽어 remaining/closeAt을 한 번에 얻었다 - 이건
-- 다른 모듈의 내부 데이터스토어를 직접 침범하는 것이라(진짜 MSA라면 애초에 접근조차
-- 불가능) 없앴다. 이제 `remaining`은 이 큐가 이미 갖고 있는 두 값(`total`, `reserved`)의
-- 뺄셈으로 계산한다 - `total - reserved`가 product의 실제 remaining과 수학적으로 항상
-- 같다는 게 증명됨(reserved는 CREATED/CANCELLED 이벤트로 큐가 정확히 누적하는 값).
-- closeAt/limitPerUser는 queue 소유 `drop-meta:{dropId}` 캐시(부트스트랩 시 product REST
-- 1회 호출로 채움)에서 읽는다.
--
-- KEYS[1]=admission:{dropId}:{userId}  (STRING, 입장권 - 값은 발급 수량)
-- KEYS[2]=queue:{dropId}               (ZSET, 대기 순번)
-- KEYS[3]=queue:{dropId}:heartbeat     (ZSET)
-- KEYS[4]=queue:{dropId}:qty           (HASH, userId -> 요청수량)
-- KEYS[5]=drop-meta:{dropId}           (HASH, queue 소유 - closeAt/limitPerUser, 부트스트랩 캐시)
-- KEYS[6]=outstanding:{dropId}         (STRING)
-- KEYS[7]=confirmed:{dropId}           (STRING)
-- KEYS[8]=total:{dropId}               (STRING - 캐시 miss면 앱이 product REST로 폴백)
-- KEYS[9]=decision:{dropId}            (HASH, userId -> 'WAIT_CONFIRMED:<max|NA>' | 'ASKED:<epochMs>')
-- KEYS[10]=reserved:{dropId}           (STRING - 선점 누적 수량, CREATED/CANCELLED로 가감)
--
-- ARGV[1]=userId  ARGV[2]=now(epoch ms)  ARGV[3]=touchHeartbeat('1'|'0')
--
-- 반환(고정 11칸, 전부 문자열 - StringRedisTemplate의 List<String> 계약, 부재는 '-'):
-- {admittedQty, rank, totalWaiting, quantity, remaining, closeAt, outstanding, confirmed, total, decision, reserved}
--  READY(입장권 보유)면 1번째만 채워 조기 반환하고, 대기열에 없으면 2번째가 '-'다.

local userId = ARGV[1]

local admittedQty = redis.call('GET', KEYS[1])
if admittedQty then
  return {admittedQty, '-', '0', '-', '-', '-', '0', '0', '-', '-', '0'}
end

local rank = redis.call('ZRANK', KEYS[2], userId)
if rank == false then
  return {'-', '-', '0', '-', '-', '-', '0', '0', '-', '-', '0'}
end

if ARGV[3] == '1' then
  redis.call('ZADD', KEYS[3], ARGV[2], userId)
end

local totalWaiting = redis.call('ZCARD', KEYS[2])
local qty = redis.call('HGET', KEYS[4], userId)
local closeAt = redis.call('HGET', KEYS[5], 'closeAt')
local outstanding = redis.call('GET', KEYS[6])
local confirmed = redis.call('GET', KEYS[7])
local total = redis.call('GET', KEYS[8])
local decision = redis.call('HGET', KEYS[9], userId)
local reserved = redis.call('GET', KEYS[10])

local function opt(v)
  if v then return v else return '-' end
end

-- remaining = total - reserved. total이 없으면(부트스트랩 REST 미완료) product 캐시
-- 미워밍이었던 예전과 동일하게 "없음"으로 저하시킨다(성급히 판단하지 않고 대기시킴).
local remaining = '-'
if total then
  local computed = tonumber(total) - tonumber(reserved or '0')
  -- 방어적 클램프(위생 코드일 뿐 정합성 보정 수단이 아님 - 실제 드리프트를 고치는 게
  -- 아니라 이상값이 화면/로그로 새어나가는 것만 막는다).
  if computed < 0 then computed = 0 end
  if computed > tonumber(total) then computed = tonumber(total) end
  remaining = tostring(computed)
end

return {
  '-',
  tostring(rank),
  tostring(totalWaiting),
  opt(qty),
  remaining,
  opt(closeAt),
  outstanding or '0',
  confirmed or '0',
  opt(total),
  opt(decision),
  reserved or '0',
}
