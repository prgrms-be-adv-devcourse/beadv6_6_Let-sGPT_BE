-- 권위 있는 drop 재고 스냅샷 전체 교체
-- KEYS[1]=drop:{id}  KEYS[2]=drop:{id}:buyers  KEYS[3]=recovery owner
-- ARGV[1]=remaining  ARGV[2]=openAt  ARGV[3]=closeAt
-- ARGV[4]=limitPerUser  ARGV[5]=ttl(ms)  ARGV[6]=owner
-- ARGV[7...]=buyerId,quantity 쌍

if redis.call('GET', KEYS[3]) ~= ARGV[6] then
  return 'STALE_OWNER'
end

redis.call('DEL', KEYS[1], KEYS[2])
redis.call(
    'HSET', KEYS[1],
    'remaining', ARGV[1],
    'openAt', ARGV[2],
    'closeAt', ARGV[3],
    'limitPerUser', ARGV[4])

for index = 7, #ARGV, 2 do
  redis.call('HSET', KEYS[2], ARGV[index], ARGV[index + 1])
end

local ttl = tonumber(ARGV[5])
if ttl > 0 then
  redis.call('PEXPIRE', KEYS[1], ttl)
  if redis.call('EXISTS', KEYS[2]) == 1 then
    redis.call('PEXPIRE', KEYS[2], ttl)
  end
end

return 'OK'
