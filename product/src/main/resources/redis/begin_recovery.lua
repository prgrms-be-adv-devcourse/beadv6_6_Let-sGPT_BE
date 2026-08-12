-- Atomically exclude both another recovery and all admitted stock writers.
-- KEYS[1]=recovery owner, KEYS[2]=inflight attempts; ARGV[1]=owner, ARGV[2]=lease ms.
if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('SCARD', KEYS[2]) ~= 0 then
  return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
return 1
