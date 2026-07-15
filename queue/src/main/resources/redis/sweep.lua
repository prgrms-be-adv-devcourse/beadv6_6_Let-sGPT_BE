-- 하트비트 끊긴(이탈) 대기자를 대기열/하트비트/수량/결정상태 전부에서 회수(단일 원자 실행)
-- KEYS[1]=queue:{dropId}  KEYS[2]=queue:{dropId}:heartbeat  KEYS[3]=queue:{dropId}:qty
-- KEYS[4]=decision:{dropId} (Phase B - 대화형 결정 상태, 있으면 함께 정리)
-- ARGV[1]=cutoff(epoch ms) - 이 시각보다 하트비트가 이전인 대기자를 만료 처리
-- 반환: 회수된 인원 수

local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[1])
if #expired == 0 then
  return 0
end

redis.call('ZREM', KEYS[2], unpack(expired))
redis.call('ZREM', KEYS[1], unpack(expired))
redis.call('HDEL', KEYS[3], unpack(expired))
redis.call('HDEL', KEYS[4], unpack(expired))
return #expired
