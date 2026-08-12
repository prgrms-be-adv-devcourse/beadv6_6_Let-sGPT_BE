-- DB CLOSE 롤백 시 판매 종료 시각만 복구한다. remaining과 buyers는 drain 변경을 보존한다.
-- KEYS[1]=drop:{id}  ARGV[1]=closeAt(epoch ms, -1이면 미설정)

if redis.call('EXISTS', KEYS[1]) == 1 then
  redis.call('HSET', KEYS[1], 'closeAt', ARGV[1])
end
return 'OK'
