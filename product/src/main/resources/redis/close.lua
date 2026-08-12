-- 드롭 종료 표시: closeAt=now로 신규 주문만 차단하고, 이미 선점한 in-flight는 TTL 동안 그대로 둔다(evict 아님)
-- KEYS[1]=drop:{id}  KEYS[2]=drop:{id}:buyers
-- ARGV[1]=최소 drain TTL(ms)

if redis.call('EXISTS', KEYS[1]) == 1 then
  local redisTime = redis.call('TIME')
  local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
  redis.call('HSET', KEYS[1], 'closeAt', now)

  local margin = tonumber(ARGV[1])
  for index = 1, 2 do
    local ttl = redis.call('PTTL', KEYS[index])
    if ttl >= 0 and ttl < margin then
      redis.call('PEXPIRE', KEYS[index], margin)
    end
  end
end
return 'OK'
