-- Owner-only PUBLIC WORLDS UI CAS. No authenticated authority or initial-LOGIN state.
-- One tenantless full-hashed key; caller-observed time bounds UI expiry only, never authority.
-- Returns closed status strings; all refusals are non-mutating. No automatic CAS retry.
local maximum = 2097152
if #KEYS ~= 1 or #ARGV ~= 5 then return 'INVALID_ARGS' end
local prefix = 'session:game:direct-text-worlds:v1:'
local key = KEYS[1]
if #key ~= #prefix + 36 or string.sub(key, 1, #prefix) ~= prefix then
  return 'INVALID_ARGS'
end
local uuid = string.sub(key, #prefix + 1)
if not string.match(uuid, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-4%x%x%x%-[89ab]%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
    or string.lower(uuid) ~= uuid then return 'INVALID_ARGS' end
local operation, expected, desired, deadline = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
local observedNow = ARGV[5]
local function positiveSafeInteger(value)
  if #value > 16 or not string.match(value, '^[1-9][0-9]*$') then return nil end
  local number = tonumber(value)
  if not number or number > 9007199254740991 then return nil end
  return number
end
local now = positiveSafeInteger(observedNow)
if not now then return 'INVALID_ARGS' end
if #expected > maximum or #desired > maximum then return 'INVALID_ARGS' end
if operation ~= 'REPLACE' and operation ~= 'CLEAR' then return 'INVALID_ARGS' end
if operation == 'CLEAR' then
  if desired ~= '' or deadline ~= '0' then return 'INVALID_ARGS' end
else
  local expiry = positiveSafeInteger(deadline)
  if #desired == 0 or not expiry then return 'INVALID_ARGS' end
  if expiry <= now then return 'EXPIRED_MENU' end
  if expiry - now > 300000 then return 'INVALID_ARGS' end
end
local kind = redis.call('TYPE', key).ok
if kind ~= 'none' and kind ~= 'string' then return 'INVALID_ARGS' end
if kind == 'string' and redis.call('STRLEN', key) > maximum then return 'INVALID_ARGS' end
local current = redis.call('GET', key)
-- An empty byte preimage denotes absence only, never an existing empty string.
if (expected == '' and current ~= false) or (expected ~= '' and current ~= expected) then
  return 'PREIMAGE_MISMATCH'
end
if operation == 'CLEAR' then
  if current == false then return 'MENU_UNCHANGED' end
  redis.call('DEL', key)
  return 'MENU_CLEARED'
end
if current == desired then return 'MENU_UNCHANGED' end
redis.call('SET', key, desired, 'PXAT', deadline)
return 'MENU_WRITTEN'
