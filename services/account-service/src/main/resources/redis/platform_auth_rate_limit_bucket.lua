-- One-key platform-auth bucket. Values contain only a keyed integrity tag and a bounded counter.
local operation = ARGV[1]
local fingerprint = ARGV[2]
local ttl_ms = tonumber(ARGV[3])
local maximum = tonumber(ARGV[4])

if operation ~= 'read' and operation ~= 'increment' then
  return -2
end
if not fingerprint or fingerprint == '' then
  return -2
end
if operation == 'increment' and (not ttl_ms or ttl_ms < 1 or not maximum or maximum < 1) then
  return -2
end

local stored_fingerprint = redis.call('HGET', KEYS[1], 'fingerprint')
if not stored_fingerprint then
  if redis.call('EXISTS', KEYS[1]) ~= 0 then
    return -2
  end
  if operation == 'read' then
    return 0
  end
  redis.call('HSET', KEYS[1], 'fingerprint', fingerprint, 'count', '0')
  redis.call('PEXPIRE', KEYS[1], ttl_ms)
  stored_fingerprint = fingerprint
end

if stored_fingerprint ~= fingerprint then
  return -1
end

local raw_count = redis.call('HGET', KEYS[1], 'count')
if not raw_count then
  return -2
end
local count = tonumber(raw_count)
if not count or count < 0 then
  return -2
end

-- Existing buckets must already have a live bounded TTL; corruption is not repaired on the hot path.
if redis.call('PTTL', KEYS[1]) < 1 then
  return -2
end

if operation == 'read' then
  return count
end

if count < maximum then
  count = redis.call('HINCRBY', KEYS[1], 'count', 1)
  if count > maximum then
    count = maximum
    redis.call('HSET', KEYS[1], 'count', tostring(maximum))
  end
end
return count
