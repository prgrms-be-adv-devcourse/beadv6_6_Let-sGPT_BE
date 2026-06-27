-- 드롭 종료 표시: closeAt=now로 신규 주문만 차단하고, 이미 선점한 in-flight는 TTL 동안 그대로 둔다(evict 아님)
-- KEYS[1]=drop:{id}  ARGV[1]=now(epoch ms)

if redis.call('EXISTS', KEYS[1]) == 1 then
  redis.call('HSET', KEYS[1], 'closeAt', ARGV[1])
end
return 'OK'
