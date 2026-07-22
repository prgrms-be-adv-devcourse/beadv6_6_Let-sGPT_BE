-- 다운스트림 장애(5xx/연결오류)로 주문이 실패했을 때, 이미 GETDEL로 소진해버린 입장권을
-- 남은 유효시간만큼 되살린다(단일 원자 실행). 사용자 잘못이 아닌 서버 장애로 몇 시간
-- 기다린 순번을 잃고 대기열 맨 뒤로 쫓겨나는 일을 막는 복구 장치다.
--
-- 원리("소진의 역연산"): GETDEL이 지운 admission 키를 같은 수량으로 다시 SET하고,
-- release(outstanding 차감/admitted 제거)는 하지 않는다 - 시스템 상태가 "주문 버튼을
-- 누르기 직전"으로 돌아가 사용자는 다음 폴링에서 다시 READY를 보고 재시도할 수 있다.
--
-- 안전장치:
-- 1. admitted ZSET에 아직 남아있는(= 스위퍼가 회수하지 않은) 경우에만 복구한다 - 요청이
--    오래 걸리는 사이 TTL이 지나 이미 회수됐다면 정당한 만료이므로 되살리지 않는다.
-- 2. 남은 유효시간이 1초 미만이면 복구해도 쓸 수 없으므로 하지 않는다(스위퍼가 곧 회수).
--
-- 안전 전제(확인됨): 주문이 실제로는 성공했는데 응답만 유실(타임아웃)된 경우 티켓이 복구돼
-- 재시도가 가능해지는데, order의 주문 생성이 idempotencyKey 기반으로 이미 멱등하다
-- (CreateOrderRequest.idempotencyKey 필수 + Order의 (member_id, idempotency_key) 유니크
-- 제약 + OrderCreationService.create()가 같은 키의 기존 주문을 그대로 replay - 동시 요청
-- 레이스도 DataIntegrityViolationException 캐치 후 재조회로 처리됨). 그래서 같은 키로 재시도되면
-- 중복 주문은 생기지 않고 기존 주문이 반환된다. 단, 이 보장은 "재시도가 같은 idempotencyKey를
-- 재사용한다"는 클라이언트 쪽 조건에 의존한다 - 재시도마다 새 키를 발급하면 이 전제가 깨진다
-- (FE가 실제로 재사용하도록 구현됨: queue/entry 실패 시 재시도 로직 참고).
--
-- KEYS[1]=admitted:{dropId}           (ZSET, member=userId, score=입장권 만료 epoch ms)
-- KEYS[2]=admission:{dropId}:{userId} (STRING, 값=발급 수량)
--
-- ARGV[1]=userId  ARGV[2]=qty(GETDEL로 읽어뒀던 발급 수량)  ARGV[3]=now(epoch ms)
--
-- 반환: 1=복구됨, 0=복구 안 함(이미 회수됐거나 만료 임박 - 사용자는 재진입 필요)

local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if score == false then
  return 0
end

local ttlMs = tonumber(score) - tonumber(ARGV[3])
if ttlMs < 1000 then
  return 0
end

redis.call('SET', KEYS[2], ARGV[2], 'PX', ttlMs)
return 1
