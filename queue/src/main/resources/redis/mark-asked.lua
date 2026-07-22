-- DECISION_REQUIRED를 처음 노출하는 순간 "물어본 시각"을 기록한다(무응답 이탈 처리의 기준점).
--
-- HSETNX 의미론: 이미 값이 있으면(재폴링으로 다시 물어보는 중이거나 WAIT 확정자) 덮어쓰지
-- 않는다 - 마감 시각(deadline)이 폴링마다 뒤로 밀리면 타임아웃이 영원히 안 오기 때문.
--
-- KEYS[1]=decision:{dropId}  (HASH, userId -> 'WAIT_CONFIRMED:<max|NA>' | 'ASKED:<epochMs>')
-- ARGV[1]=userId  ARGV[2]=now(epoch ms)
--
-- 반환: 이 사용자에게 적용되는 askedAt(epoch ms - 방금 기록했으면 now, 이미 있었으면 기존 값).
--   WAIT_CONFIRMED면 -1 - 명시적으로 "기다리겠다"고 답한 사람은 재질의(SHORTFALL) 중이어도
--   타임아웃 제거 대상이 아니라는 정책(제거는 무응답자만).

local cur = redis.call('HGET', KEYS[1], ARGV[1])
if cur == false then
  redis.call('HSET', KEYS[1], ARGV[1], 'ASKED:' .. ARGV[2])
  return tonumber(ARGV[2])
end
if string.sub(cur, 1, 6) == 'ASKED:' then
  return tonumber(string.sub(cur, 7))
end
return -1
