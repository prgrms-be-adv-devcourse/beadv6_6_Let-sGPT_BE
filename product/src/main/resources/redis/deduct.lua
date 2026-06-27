-- 재고 차감 게이트키퍼 (단일 원자 실행)
-- KEYS[1]=drop:{id}  KEYS[2]=drop:{id}:buyers  KEYS[3]=order:{orderId}
-- ARGV[1]=buyerId  ARGV[2]=quantity  ARGV[3]=now(epoch ms)  ARGV[4]=멱등키 TTL(초)
-- 반환: "STATUS:remaining" (실패 시 remaining=-1)

if redis.call('EXISTS', KEYS[1]) == 0 then
  return 'NOT_CACHED:-1'
end

if redis.call('EXISTS', KEYS[3]) == 1 then
  return 'DUPLICATE:' .. redis.call('GET', KEYS[3])
end

local openAt = tonumber(redis.call('HGET', KEYS[1], 'openAt'))
local closeAt = tonumber(redis.call('HGET', KEYS[1], 'closeAt'))
local limit = tonumber(redis.call('HGET', KEYS[1], 'limitPerUser'))
local remaining = tonumber(redis.call('HGET', KEYS[1], 'remaining'))
local now = tonumber(ARGV[3])
local qty = tonumber(ARGV[2])

if now < openAt then
  return 'NOT_OPEN:-1'
end

if closeAt >= 0 and now >= closeAt then
  return 'CLOSED:-1'
end

if limit >= 0 then
  local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
  if bought + qty > limit then
    return 'LIMIT_EXCEEDED:-1'
  end
end

if remaining < qty then
  return 'SOLD_OUT:' .. remaining
end

local newRemaining = remaining - qty
redis.call('HSET', KEYS[1], 'remaining', newRemaining)
redis.call('HINCRBY', KEYS[2], ARGV[1], qty)

-- buyers 해시가 drop 해시와 함께 만료되도록 TTL을 맞춘다
local pttl = redis.call('PTTL', KEYS[1])
if pttl > 0 then
  redis.call('PEXPIRE', KEYS[2], pttl)
end

redis.call('SET', KEYS[3], newRemaining)
local idemTtl = tonumber(ARGV[4])
if idemTtl > 0 then
  redis.call('EXPIRE', KEYS[3], idemTtl)
end

return 'OK:' .. newRemaining
