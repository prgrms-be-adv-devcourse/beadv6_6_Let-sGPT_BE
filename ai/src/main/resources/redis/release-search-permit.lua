-- 검색 동시성 permit 반납
-- KEYS[1]=permit ZSET 키  ARGV[1]=반납할 permit id

redis.call('ZREM', KEYS[1], ARGV[1])
return 1
