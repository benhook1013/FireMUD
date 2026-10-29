-- Exact atomic readback for one live grant and its complete separate canonical timeline epoch.
local leaseKey = KEYS[1]
local metaKey = KEYS[2]
if #KEYS ~= 2 or #ARGV ~= 3 or ARGV[1] ~= 'gs_region_lease/v1' then return -1 end
local token = ARGV[2]
local expectedEpoch = ARGV[3]

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
  if tickId == '-1' then return state == 'APPLIED' and not terminalAt end
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
  or not positiveInt64(expectedEpoch) then return -1 end
if not validMeta()
  or redis.call('TYPE', leaseKey).ok ~= 'string'
  or redis.call('PTTL', leaseKey) <= 0
  or redis.call('GET', leaseKey) ~= token then return 0 end
return 1
