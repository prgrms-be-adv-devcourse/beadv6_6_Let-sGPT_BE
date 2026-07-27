-- 대기열 자리 완전 회수(단일 원자 실행). 예전엔 Mono.when으로 아래 4개 삭제를 병렬 실행했는데
-- (개별 네트워크 왕복 4번), 원자적이지 않아 그 사이에 enqueue-or-admit.lua/admit.lua 같은 다른
-- 스크립트가 끼어들면 일부만 지워진 상태(예: 순번 ZSET은 지워졌는데 하트비트/수량 해시는 남는
-- 등)가 생길 수 있었다. SSE 연결 끊김마다 이 함수가 불리게 되면서(QueueStreamService 즉시 회수
-- 경로) 호출 빈도가 늘어 그 레이스가 실제로 부딪힐 확률도 함께 늘었다 - 단일 Lua로 원자화한다.
--
-- KEYS[1]=queue:{dropId}            (ZSET, 대기 순번)
-- KEYS[2]=queue:{dropId}:heartbeat  (ZSET)
-- KEYS[3]=queue:{dropId}:qty        (HASH, userId -> 요청수량)
-- KEYS[4]=decision:{dropId}         (HASH, userId -> 결정 상태)
--
-- ARGV[1]=userId
--
-- 반환: 실제로 대기 순번을 갖고 있었으면 1, 이미 없었으면(중복 호출 등) 0.
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('HDEL', KEYS[4], ARGV[1])
return removed
