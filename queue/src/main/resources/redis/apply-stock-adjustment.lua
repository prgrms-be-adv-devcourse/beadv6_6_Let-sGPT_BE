-- StockAdjustment(결제 확정/환불) 이벤트를 확정 수량 카운터에 원자적으로 반영한다.
--
-- 원자화 이유: 예전엔 앱이 SADD(멱등 마킹)와 INCRBY(카운터 가감)를 별도 명령 두 번으로
-- 보냈는데, 그 사이에 앱이 크래시하면 "이벤트는 반영됨으로 마킹됐지만 카운터는 안 움직인"
-- 영구 drift가 생긴다(Kafka 재전달이 와도 seen에 있어 무시됨). 스크립트 하나 = 네트워크
-- 호출 한 번이므로 앱 관점에서 둘 다 되거나 둘 다 안 되거나만 남는다. Redis가 마지막 쓰기를
-- 잃는 장애에서도 두 키가 함께 사라지므로 재전달 시 깨끗하게 재반영된다.
--
-- KEYS[1]=confirmed:{dropId}:seen  (SET, 이미 반영한 eventId 집합 - 멱등 처리)
-- KEYS[2]=confirmed:{dropId}       (STRING, 확정 누적 수량 - 환불로 감소 가능)
--
-- ARGV[1]=eventId  ARGV[2]=delta(정수 문자열 - COMPLETED면 +count, REFUNDED면 -count)
--
-- 반환: 1=이번에 반영됨, 0=이미 반영된 eventId라 무시(재전달 멱등 처리)

if redis.call('SADD', KEYS[1], ARGV[1]) == 1 then
  redis.call('INCRBY', KEYS[2], ARGV[2])
  return 1
end
return 0
