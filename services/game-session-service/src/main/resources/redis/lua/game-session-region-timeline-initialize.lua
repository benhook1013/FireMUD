-- Proves a truly absent timeline, then initializes its complete baseline with the first lease.
local leaseKey = KEYS[1]
local metaKey = KEYS[2]
if #KEYS ~= 2 or #ARGV ~= 8 or ARGV[1] ~= 'gs_region_timeline_init/v1' then return -1 end
local action = ARGV[2]
local token = ARGV[3]
local expectedEpoch = ARGV[4]
local operationId = ARGV[5]
local sourceDigest = ARGV[6]
local ttl = ARGV[7]
local mayCreate = ARGV[8]

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

local function validTimelineMeta()
  if redis.call('TYPE', metaKey).ok ~= 'hash'
    or redis.call('PTTL', metaKey) ~= -1
    or redis.call('HGET', metaKey, 'region_epoch') ~= expectedEpoch
    or redis.call('HGET', metaKey, 'first_timeline_operation_id') ~= operationId
    or redis.call('HGET', metaKey, 'first_timeline_source_digest') ~= sourceDigest then return false end
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
  or (action ~= 'PROBE' and action ~= 'INITIALIZE' and action ~= 'VERIFY')
  or not validUuid(token)
  or expectedEpoch ~= '1'
  or not validUuid(operationId)
  or not string.match(sourceDigest, '^[0-9a-f]+$') or string.len(sourceDigest) ~= 64
  or not positiveInt64(ttl)
  or (mayCreate ~= '0' and mayCreate ~= '1') then return -1 end

local leaseType = redis.call('TYPE', leaseKey).ok
local metaType = redis.call('TYPE', metaKey).ok
if (leaseType ~= 'none' and leaseType ~= 'string')
  or (metaType ~= 'none' and metaType ~= 'hash') then return -2 end

if action == 'PROBE' then
  if leaseType == 'none' and metaType == 'none' then return 1 end
  return 0
end

if action == 'VERIFY' then
  if not validTimelineMeta()
    or leaseType ~= 'string'
    or redis.call('PTTL', leaseKey) <= 0
    or redis.call('GET', leaseKey) ~= token then return 0 end
  return 3
end

if metaType == 'none' then
  if leaseType ~= 'none' or mayCreate ~= '1' then return 0 end
  redis.call('HSET', metaKey,
    'region_epoch', expectedEpoch,
    'current_tick_id', '-1',
    'current_tick_state', 'APPLIED',
    'first_timeline_operation_id', operationId,
    'first_timeline_source_digest', sourceDigest)
elseif not validTimelineMeta() then
  return 0
end

if leaseType == 'string' then
  local pttl = redis.call('PTTL', leaseKey)
  if pttl <= 0 then return 0 end
  if redis.call('GET', leaseKey) == token then return 2 end
  return 0
end

local result = redis.call('SET', leaseKey, token, 'PX', ttl, 'NX')
if result and result.ok == 'OK' then return 1 end
return 0
