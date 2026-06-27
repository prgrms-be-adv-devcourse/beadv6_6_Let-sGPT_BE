-- 재고 롤백(복원) 게이트키퍼 (단일 원자 실행)
-- KEYS[1]=drop:{id}  KEYS[2]=drop:{id}:buyers  KEYS[3]=order:{orderId}:rollback
-- ARGV[1]=buyerId  ARGV[2]=quantity  ARGV[3]=멱등키 TTL(초)
-- 반환: "STATUS:remaining" (캐시 없으면 remaining=-1)

if redis.call('EXISTS', KEYS[1]) == 0 then
  return 'NOT_CACHED:-1'
end

if redis.call('EXISTS', KEYS[3]) == 1 then
  return 'DUPLICATE:' .. redis.call('GET', KEYS[3])
end

local qty = tonumber(ARGV[2])
local newRemaining = redis.call('HINCRBY', KEYS[1], 'remaining', qty)

local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
local newBought = bought - qty
if newBought > 0 then
  redis.call('HSET', KEYS[2], ARGV[1], newBought)
else
  redis.call('HDEL', KEYS[2], ARGV[1])
end

redis.call('SET', KEYS[3], newRemaining)
local idemTtl = tonumber(ARGV[3])
if idemTtl > 0 then
  redis.call('EXPIRE', KEYS[3], idemTtl)
end

return 'OK:' .. newRemaining
