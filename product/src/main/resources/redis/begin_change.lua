-- Admission covers the entire Redis mutation -> database commit/compensation interval.
-- KEYS[1]=recovery owner, KEYS[2]=inflight attempts; ARGV[1]=unique attempt UUID.
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
-- No TTL: elapsed time is not proof that a database writer has stopped.
redis.call('SADD', KEYS[2], ARGV[1])
return 1
