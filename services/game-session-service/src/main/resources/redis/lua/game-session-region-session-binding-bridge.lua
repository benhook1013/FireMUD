-- One exact first-NEW binding write/readback under the current region lease and timeline.
local leaseKey = KEYS[1]
local metaKey = KEYS[2]
local bindingKey = KEYS[3]
if #KEYS ~= 3 or #ARGV ~= 6 or ARGV[1] ~= 'gs_region_binding_bridge/v1' then
  return -1
end

local action = ARGV[2]
local token = ARGV[3]
local expectedEpoch = ARGV[4]
local sessionId = ARGV[5]
local generation = ARGV[6]
if action ~= 'APPLY' and action ~= 'READBACK' and action ~= 'PROBE' then return -1 end

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

local function positiveGeneration(value)
  return string.match(value, '^[1-9][0-9]*$') and string.len(value) <= 128
end

local tag1 = string.match(leaseKey, '^tick%-executor%-lease:{([^{}:]+)}$')
local tag2 = string.match(metaKey, '^tick:{([^{}:]+)}:meta$')
local tag3, entityId = string.match(bindingKey, '^tick:{([^{}:]+)}:session%-binding:([1-9][0-9]*)$')
local tagTenant, tagGame, tagRegion =
  string.match(tag1 or '', '^r1%-([0-9a-f]+)%-([0-9a-f]+)%-([0-9a-f]+)$')
if not tag1 or tag1 ~= tag2 or tag1 ~= tag3
  or string.len(tag1) ~= 101
  or not tagTenant or string.len(tagTenant) ~= 32
  or string.len(tagGame) ~= 32 or string.len(tagRegion) ~= 32
  or not positiveInt64(entityId)
  or not validUuid(token)
  or not positiveInt64(expectedEpoch)
  or not validUuid(sessionId)
  or not positiveGeneration(generation) then return -1 end

local leaseType = redis.call('TYPE', leaseKey).ok
if leaseType ~= 'string' or redis.call('PTTL', leaseKey) <= 0
  or redis.call('GET', leaseKey) ~= token then return 0 end

local metaType = redis.call('TYPE', metaKey).ok
if metaType ~= 'hash'
  or redis.call('PTTL', metaKey) ~= -1
  or redis.call('HGET', metaKey, 'region_epoch') ~= expectedEpoch then return 0 end
local currentTickId = redis.call('HGET', metaKey, 'current_tick_id')
local currentTickState = redis.call('HGET', metaKey, 'current_tick_state')
local currentTickTerminalAt = redis.call('HGET', metaKey, 'current_tick_terminal_at_ms')
if not currentTickId then return 0 end
if currentTickId == '-1' then
  if currentTickState ~= 'APPLIED' or currentTickTerminalAt then return 0 end
elseif not nonnegativeInt64(currentTickId) then
  return 0
elseif currentTickState == 'STAGED' or currentTickState == 'RESOLVING' then
  if currentTickTerminalAt then return 0 end
elseif currentTickState == 'APPLIED' or currentTickState == 'ABANDONED' then
  if not positiveInt64(currentTickTerminalAt or '') then return 0 end
else
  return 0
end

local bindingType = redis.call('TYPE', bindingKey).ok
if bindingType == 'hash' and redis.call('PTTL', bindingKey) ~= -1 then return 0 end
if action == 'PROBE' then
  if bindingType == 'none' then return 4 end
  if bindingType ~= 'hash' or redis.call('HLEN', bindingKey) ~= 2 then return 0 end
  if redis.call('HGET', bindingKey, 'session_id') == sessionId
    and redis.call('HGET', bindingKey, 'binding_generation') == generation then return 5 end
  return 0
end
if action == 'READBACK' then
  if bindingType ~= 'hash' or redis.call('HLEN', bindingKey) ~= 2
    or redis.call('HGET', bindingKey, 'session_id') ~= sessionId
    or redis.call('HGET', bindingKey, 'binding_generation') ~= generation then return 0 end
  return 3
end

if bindingType == 'none' then
  redis.call('HSET', bindingKey,
    'session_id', sessionId,
    'binding_generation', generation)
  return 1
end
if bindingType ~= 'hash' or redis.call('HLEN', bindingKey) ~= 2 then return 0 end
if redis.call('HGET', bindingKey, 'session_id') == sessionId
  and redis.call('HGET', bindingKey, 'binding_generation') == generation then return 2 end
return 0
