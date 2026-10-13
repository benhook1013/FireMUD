if #KEYS ~= 1 or #ARGV ~= 2 then
  return -4
end

local key = KEYS[1]
if not string.match(key, '^session:auth:token:[0-9a-f]+$')
    or string.len(key) ~= string.len('session:auth:token:') + 64 then
  return -4
end

local record = ARGV[1]
if not record or string.len(record) == 0 or string.len(record) > 16384 then
  return -4
end

local targetExpiry = tonumber(ARGV[2])
if not targetExpiry or targetExpiry <= 0 or targetExpiry > 9007199254740991
    or targetExpiry ~= math.floor(targetExpiry) then
  return -4
end

local current = redis.call('GET', key)
if current then
  if current ~= record then
    return -1
  end
  local currentExpiry = redis.call('PEXPIRETIME', key)
  if currentExpiry ~= targetExpiry then
    return -2
  end
  redis.call('SET', key, record, 'PXAT', ARGV[2])
  return 0
end

local stored = redis.call('SET', key, record, 'PXAT', ARGV[2], 'NX')
if stored ~= 'OK' then
  return -3
end
return 1
