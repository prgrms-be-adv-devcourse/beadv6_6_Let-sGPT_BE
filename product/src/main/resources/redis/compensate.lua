-- 차감/롤백 보상(역연산): 캐시 효과를 되돌리고 멱등키를 제거해 재시도가 가능하게 한다
-- KEYS[1]=drop:{id}  KEYS[2]=drop:{id}:buyers  KEYS[3]=멱등키
-- ARGV[1]=buyerId  ARGV[2]=remainingDelta  ARGV[3]=buyerDelta
-- (차감 보상: remainingDelta=+qty, buyerDelta=-qty / 롤백 보상: remainingDelta=-qty, buyerDelta=+qty)

if redis.call('EXISTS', KEYS[1]) == 1 then
  redis.call('HINCRBY', KEYS[1], 'remaining', tonumber(ARGV[2]))
  local newBought = redis.call('HINCRBY', KEYS[2], ARGV[1], tonumber(ARGV[3]))
  if newBought <= 0 then
    redis.call('HDEL', KEYS[2], ARGV[1])
  end
end

redis.call('DEL', KEYS[3])
return 'OK'
