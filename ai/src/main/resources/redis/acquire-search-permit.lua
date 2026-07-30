-- 검색 동시성 permit 획득 (슬라이딩 윈도우 ZSET)
-- KEYS[1]=permit ZSET 키
-- ARGV[1]=now(epoch ms)  ARGV[2]=permit TTL(ms)  ARGV[3]=최대 동시성  ARGV[4]=permit id
-- 반환: 1=획득, 0=상한 초과

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1] - ARGV[2])
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
  return 0
end
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
