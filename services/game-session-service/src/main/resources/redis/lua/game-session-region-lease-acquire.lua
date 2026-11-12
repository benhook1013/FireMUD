-- Claims only a lease whose separate canonical timeline metadata already matches the expected epoch.
local leaseKey = KEYS[1]
local metaKey = KEYS[2]
if #KEYS ~= 2 or #ARGV ~= 5 or ARGV[1] ~= 'gs_region_lease/v2' then return -1 end
local token = ARGV[2]
local expectedEpoch = ARGV[3]
local ttl = ARGV[4]
local deadlineText = ARGV[5]
if deadlineText ~= '0' and (not string.match(deadlineText, '^[1-9][0-9]*$')
    or #deadlineText > 16 or (#deadlineText == 16 and deadlineText > '9007199254740991')) then
  return -1
end
local deadline = tonumber(deadlineText)

local function validUuid(value)
  return string.len(value) == 36
    and string.sub(value, 9, 9) == '-'
    and string.sub(value, 14, 14) == '-'
    and string.sub(value, 19, 19) == '-'
    and string.sub(value, 24, 24) == '-'
    and string.match(string.gsub(value, '%-', ''), '^[0-9a-f]+$')
    and value ~= '00000000-0000-0000-0000-000000000000'
end

local function positiveInt64(value)
  return string.match(value, '^[1-9][0-9]*$')
    and string.len(value) <= 19
    and not (string.len(value) == 19 and value > '9223372036854775807')
end

local function nonnegativeInt64(value)
  return (value == '0' or string.match(value, '^[1-9][0-9]*$'))
    and string.len(value) <= 19
    and not (string.len(value) == 19 and value > '9223372036854775807')
end

local function validMeta()
  if redis.call('TYPE', metaKey).ok ~= 'hash'
    or redis.call('PTTL', metaKey) ~= -1
    or redis.call('HGET', metaKey, 'region_epoch') ~= expectedEpoch then return false end
  local tickId = redis.call('HGET', metaKey, 'current_tick_id')
  local state = redis.call('HGET', metaKey, 'current_tick_state')
  local terminalAt = redis.call('HGET', metaKey, 'current_tick_terminal_at_ms')
  if not tickId then return false end
  if tickId == '-1' then
    return state == 'APPLIED' and not terminalAt
  end
  if not nonnegativeInt64(tickId) then return false end
  if state == 'STAGED' or state == 'RESOLVING' then return not terminalAt end
  if state == 'APPLIED' or state == 'ABANDONED' then return positiveInt64(terminalAt or '') end
  return false
end

local tag1 = string.match(leaseKey, '^tick%-executor%-lease:{([^{}:]+)}$')
local tag2 = string.match(metaKey, '^tick:{([^{}:]+)}:meta$')
local tagTenant, tagGame, tagRegion =
  string.match(tag1 or '', '^r1%-([0-9a-f]+)%-([0-9a-f]+)%-([0-9a-f]+)$')
if not tag1 or tag1 ~= tag2
  or string.len(tag1) ~= 101
  or not tagTenant or string.len(tagTenant) ~= 32
  or string.len(tagGame) ~= 32 or string.len(tagRegion) ~= 32
  or not validUuid(token)
  or not positiveInt64(expectedEpoch)
  or not string.match(ttl, '^[1-9][0-9]*$')
  or string.len(ttl) > 19
  or (string.len(ttl) == 19 and ttl > '9223372036854775807') then return -1 end
if not validMeta() then return 0 end

local keyType = redis.call('TYPE', leaseKey).ok
if keyType ~= 'none' and keyType ~= 'string' then return -2 end
if keyType == 'string' then
  if redis.call('PTTL', leaseKey) <= 0 then return 0 end
  if redis.call('GET', leaseKey) == token then return 2 end
  return 0
end
if deadline > 0 then
  local now = redis.call('TIME')
  if tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) >= deadline then
    return 0
  end
end
local result = redis.call('SET', leaseKey, token, 'PX', ttl, 'NX')
if result and result.ok == 'OK' then return 1 end
return 0
