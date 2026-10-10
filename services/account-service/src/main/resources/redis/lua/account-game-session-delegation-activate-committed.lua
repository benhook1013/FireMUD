if #KEYS ~= 1 or #ARGV ~= 3 then
  return -4
end

local key = KEYS[1]
if not string.match(key, '^session:auth:token:[0-9a-f]+$')
    or string.len(key) ~= string.len('session:auth:token:') + 64 then
  return -4
end

local pending = ARGV[1]
local active = ARGV[2]
if not pending or string.len(pending) == 0 or string.len(pending) > 16384
    or not active or string.len(active) == 0 or string.len(active) > 16384 then
  return -4
end

-- The two canonical values may differ in exactly the lifecycle pair. String operations preserve
-- every potentially large integer byte-for-byte and avoid cjson's lossy numeric conversion.
local versioned, versionCount = string.gsub(pending, '"registryVersion":1', '"registryVersion":2')
local expectedActive, stateCount = string.gsub(versioned, '"state":"pending"', '"state":"active"')
if versionCount ~= 1 or stateCount ~= 1 or expectedActive ~= active then
  return -4
end

local deadline = ARGV[3]
if not deadline or not string.match(deadline, '^[1-9][0-9]*$') or string.len(deadline) > 16 then
  return -4
end
local targetExpiry = tonumber(deadline)
if not targetExpiry or targetExpiry <= 0 or targetExpiry > 9007199254740991
    or targetExpiry ~= math.floor(targetExpiry) then
  return -4
end

local current = redis.call('GET', key)
if not current then
  return -3
end
local currentExpiry = redis.call('PEXPIRETIME', key)
if currentExpiry ~= targetExpiry then
  return -2
end

if current == pending then
  local stored = redis.call('SET', key, active, 'PXAT', deadline, 'XX')
  if stored ~= 'OK' then
    return -5
  end
  return 1
end

if current == active then
  -- Exact lost-ack retry: reassert only the original absolute deadline, never a relative TTL.
  local stored = redis.call('SET', key, active, 'PXAT', deadline, 'XX')
  if stored ~= 'OK' then
    return -5
  end
  return 0
end

return -1
