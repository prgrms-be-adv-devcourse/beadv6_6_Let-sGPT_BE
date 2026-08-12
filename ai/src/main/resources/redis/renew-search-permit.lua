-- 검색 동시성 permit 갱신 (ZADD와 PEXPIRE를 원자적으로 함께 갱신)
-- KEYS[1]=permit ZSET 키
-- ARGV[1]=now(epoch ms)  ARGV[2]=permit TTL(ms)  ARGV[3]=permit id
-- 반환: 갱신된 멤버 수(0 또는 1)

if redis.call('ZSCORE', KEYS[1], ARGV[3]) then
  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return 1
end
return 0
