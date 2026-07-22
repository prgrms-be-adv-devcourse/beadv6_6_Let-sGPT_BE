-- 즉시 입장 시도 후 실패 시 대기열 등록(단일 원자 실행). enqueue.lua를 대체한다.
--
-- 정적 hot-drops 목록이 없는 세계에서, 경쟁이 전혀 없는 드롭까지 admit.lua의 스케줄러 tick을
-- 기다리게 하면(최대 admission.interval-ms) 평범한 주문마다 불필요한 지연이 생긴다. 그래서
-- "대기 중인 사람이 아무도 없고(ZCARD==0) 요청 수량만큼 재고가 즉시 가용하면, 대기열에 넣지
-- 않고 그 자리에서 입장권을 발급"한다 - 대기열이 비어 있다는 사실 자체가 "새치기가 없다"는
-- 공정성 보장이므로 안전하다. 그 조건에 못 미치면(이미 누가 대기 중이거나 가용 재고 부족)
-- 평범하게 대기열에 등록해 다음 admit.lua tick(또는 대화형 결정)을 기다리게 한다.
--
-- 버그 이력(MSA 경계 위반 제거, queue-remaining-sync 재설계 작업): 예전엔 KEYS[4]가 product
-- 소유 `drop:{dropId}` 해시를 직접 읽었다 - 다른 모듈의 내부 데이터스토어를 직접 침범하는
-- 것이라 없앴다. `remaining`은 이제 이 큐가 이미 갖고 있는 `total - reserved`로 계산한다
-- (수학적으로 product의 실제 remaining과 항상 같음이 증명됨). closeAt은 queue 소유
-- `drop-meta:{dropId}` 캐시(부트스트랩 시 product REST 1회 호출로 채움)에서 읽는다.
--
-- KEYS[1]=queue:{dropId}            (ZSET, 대기 순번)
-- KEYS[2]=queue:{dropId}:heartbeat  (ZSET)
-- KEYS[3]=queue:{dropId}:qty        (HASH, userId -> 요청수량)
-- KEYS[4]=total:{dropId}            (STRING - 총재고, 불변값 캐시)
-- KEYS[5]=reserved:{dropId}         (STRING - 선점 누적 수량, CREATED/CANCELLED로 가감)
-- KEYS[6]=drop-meta:{dropId}        (HASH, queue 소유 - closeAt, 부트스트랩 캐시)
-- KEYS[7]=outstanding:{dropId}      (STRING, 미소진 입장권 수량 합)
-- KEYS[8]=admitted:{dropId}         (ZSET, member=userId, score=입장권 만료 epoch ms)
-- KEYS[9]=admitted:{dropId}:qty     (HASH, userId -> 발급 수량)
-- KEYS[10]=queue:active-drops       (SET, member=dropId - 동적 발견 레지스트리)
--
-- ARGV[1]=dropId  ARGV[2]=userId  ARGV[3]=quantity  ARGV[4]=입장권 TTL(초)
--
-- 반환: {admitted(1|0), quantity} - admitted=1이면 즉시 입장권 발급됨(quantity=발급 수량),
--   0이면 대기열에 등록됨(quantity=0, 이후 상태는 status 폴링으로 확인).
--
-- 순번(score) 시각은 앱 서버가 넘겨주지 않고 이 스크립트가 `TIME`으로 직접 찍는다(버그
-- 수정, 2026-07): 예전엔 QueueService.enter()가 계산한 Instant.now()(밀리초 해상도)를
-- 그대로 넘겨받아 ZADD 점수로 썼는데, 실제 동시 요청 여러 건이 같은 밀리초에 도착하면
-- 점수가 타이(tie)가 나고, 이때 Redis ZSET은 도착 순서가 아니라 member 문자열(userId,
-- 회원가입 시 발급된 UUID라 도착 순서와 무관)의 사전순으로 정렬한다 - 결과적으로 대기
-- 순번이 실제 도착 순서와 무관하게 뒤섞이는 실사용 버그가 있었다(데모에서 실제 재현됨).
-- `TIME`은 Redis 서버 자신의 시계를 마이크로초 해상도로, 이 스크립트 실행 중 원자적으로
-- 읽으므로 타이 확률이 사실상 사라지고, 여러 파드가 있어도 전부 같은 시계 하나를 기준으로
-- 삼게 되어 앱 서버 간 clock skew 문제도 함께 없어진다.
local time = redis.call('TIME')
local nowMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)  -- epoch ms(다른 스크립트와 동일 스케일 - expireAt/closeAt 비교용)
local rankScore = tonumber(time[1]) * 1000000 + tonumber(time[2])              -- epoch 마이크로초(순번 전용 - ZRANK만 쓰이므로 절대 스케일은 무관, 해상도만 중요)

local dropId, userId = ARGV[1], ARGV[2]
local quantity = tonumber(ARGV[3])
local now = nowMs
local ttl = tonumber(ARGV[4])

-- 어느 브랜치로 가든 이 dropId는 이제 스케줄러/스위퍼가 살펴봐야 할 대상이다(대기 중이거나
-- 미소진 입장권을 들고 있으므로).
redis.call('SADD', KEYS[10], dropId)

-- 마감(closeAt 경과) 검사 - admit.lua와 동일한 이중 방어. 앱 계층(QueueService.enter의
-- soldOutReason 가드)이 먼저 막지만, 그 체크와 이 스크립트 실행 사이에 마감 시각이 지나는
-- 좁은 레이스에서 fast path가 마감 후 입장권을 발급하는 걸 원자적으로 막는다(대기열 등록으로
-- 폴백 - 다음 폴링에서 SOLD_OUT(CLOSED)을 안내받고 하트비트 스위퍼가 자리를 정리한다).
local closeAt = tonumber(redis.call('HGET', KEYS[6], 'closeAt') or '-1')
local dropClosed = closeAt >= 0 and now >= closeAt

if not dropClosed and redis.call('ZCARD', KEYS[1]) == 0 then
  local total = redis.call('GET', KEYS[4])
  if total ~= false then
    local reserved = tonumber(redis.call('GET', KEYS[5]) or '0')
    local remaining = tonumber(total) - reserved
    -- 방어적 클램프(위생 코드 - 실제 드리프트를 고치는 게 아니라 이상값이 admission 계산에
    -- 새어들어가는 것만 막는다).
    if remaining < 0 then remaining = 0 end
    if remaining > tonumber(total) then remaining = tonumber(total) end
    local outstanding = tonumber(redis.call('GET', KEYS[7]) or '0')
    local available = remaining - outstanding
    if available >= quantity then
      local expireAt = now + (ttl * 1000)
      redis.call('SET', 'admission:' .. dropId .. ':' .. userId, quantity, 'EX', ttl)
      redis.call('ZADD', KEYS[8], expireAt, userId)
      redis.call('HSET', KEYS[9], userId, quantity)
      redis.call('INCRBY', KEYS[7], quantity)
      -- admit.lua와 동일한 이유로 반환값을 전부 문자열화한다: StringRedisTemplate 쪽
      -- RedisScript<List<String>>는 멀티불크 응답 원소가 전부 문자열이어야 한다 - 숫자를
      -- 그대로 반환하면 Lettuce가 Long으로 역직렬화해 List<String> 캐스팅이 실패한다.
      return {'1', tostring(quantity)}
    end
  end
  -- total 캐시 미존재(부트스트랩 REST 미완료) 또는 가용 재고 부족 - 아래로 흘러 평범하게
  -- 대기열 등록.
end

-- KEYS[1](순번)은 rankScore(마이크로초) - 타이 방지가 목적, ZRANK로만 쓰이므로 절대값 무의미.
-- KEYS[2](하트비트)는 nowMs(밀리초) 그대로 - sweep.lua가 앱 계산 epoch-ms cutoff와 직접
-- 비교하므로 스케일을 반드시 맞춰야 한다.
redis.call('ZADD', KEYS[1], 'NX', rankScore, userId)
redis.call('ZADD', KEYS[2], nowMs, userId)
redis.call('HSET', KEYS[3], userId, quantity)
return {'0', '0'}
