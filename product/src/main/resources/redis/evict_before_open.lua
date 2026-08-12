-- 오픈 전 삭제 fence. deduct와 같은 Redis 원자 경계에서 실제 실행 시각을 판정한다.
-- KEYS[1]=drop:{id}  KEYS[2]=drop:{id}:buyers
-- 반환: 1=미오픈 캐시 제거(캐시 없음 포함), 0=이미 오픈했거나 캐시가 불완전해 제거 거절

if redis.call('EXISTS', KEYS[1]) == 0 then
  redis.call('DEL', KEYS[2])
  return 1
end

local openAt = tonumber(redis.call('HGET', KEYS[1], 'openAt'))
if not openAt then
  return 0
end

local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
if now >= openAt then
  return 0
end

redis.call('DEL', KEYS[1], KEYS[2])
return 1
