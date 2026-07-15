-- 발급됐지만 소진되지 않은 입장권 중 TTL이 지난 것을 회수하고 outstanding을 되돌린다(단일 원자 실행).
-- 방치된 입장권이 영원히 재고를 묶어두지 않도록 하는 자가치유 - ExpiredWaiterSweeper와 같은 철학.
--
-- KEYS[1]=admitted:{dropId}      (ZSET, member=userId, score=만료 epoch ms)
-- KEYS[2]=admitted:{dropId}:qty  (HASH, userId -> 발급 수량)
-- KEYS[3]=outstanding:{dropId}   (STRING, 정수)
-- ARGV[1]=now(epoch ms)
--
-- 반환: 회수된 입장권 수
--
-- 멱등성 주의: 게이트웨이도 주문 응답 완료 시 같은 종류의 release를 수행한다(release-outstanding.lua).
-- 어느 쪽이 먼저 ZREM에 성공하는지로 중복 차감을 막는다 - 이 스크립트는 실제로 ZREM에 성공한
-- (즉 아직 게이트웨이 쪽에서 소진 처리되지 않은) 멤버에 대해서만 outstanding을 차감한다.

local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
if #expired == 0 then
  return 0
end

local totalQty = 0
local reclaimed = 0
local i = 1
while i <= #expired do
  local userId = expired[i]
  local removed = redis.call('ZREM', KEYS[1], userId)
  if removed == 1 then
    local qty = tonumber(redis.call('HGET', KEYS[2], userId) or '0')
    redis.call('HDEL', KEYS[2], userId)
    totalQty = totalQty + qty
    reclaimed = reclaimed + 1
  end
  i = i + 1
end

if totalQty > 0 then
  redis.call('DECRBY', KEYS[3], totalQty)
end

return reclaimed
