-- Install or monotonically advance one exact tenant-local CLOSED account barrier.
-- One hash-tagged key only; this script never opens admission or touches the global account index.
local prefix = 'session:game:account-admission:'
local schemaValue = 'gs_account_admission_barrier/v1'
local fields = {
  'schema', 'accountId', 'tenantId', 'playableStateNamespaceId', 'tenantGameplayTag',
  'ownerOperationId', 'ownerFenceEpoch', 'incarnationDigest', 'state'
}
local maxDecimalBytes = 256

local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
      and value ~= '00000000-0000-0000-0000-000000000000'
end

local function positiveDecimal(value)
  return type(value) == 'string' and #value > 0 and #value <= maxDecimalBytes
      and string.match(value, '^[1-9][0-9]*$') ~= nil
end

local function nonnegativeDecimal(value)
  return type(value) == 'string' and #value > 0 and #value <= maxDecimalBytes
      and (value == '0' or string.match(value, '^[1-9][0-9]*$') ~= nil)
end

local function decimalLess(left, right)
  if #left ~= #right then return #left < #right end
  for index = 1, #left do
    local leftDigit = string.byte(left, index)
    local rightDigit = string.byte(right, index)
    if leftDigit ~= rightDigit then return leftDigit < rightDigit end
  end
  return false
end

local function tag(value)
  if type(value) ~= 'string' or #value > 44 then return false end
  local version, opaque = string.match(value, '^g([1-9][0-9]*)%-([0-9a-f]+)$')
  return version ~= nil and #version <= 10 and #opaque == 32
end

local function digest(value)
  return type(value) == 'string' and #value == 71
      and string.match(value, '^sha256:[0-9a-f]+$') ~= nil
end

if #KEYS ~= 1 or #ARGV ~= 9 then return -2 end
local schema = ARGV[1]
local accountId = ARGV[2]
local tenantId = ARGV[3]
local namespaceId = ARGV[4]
local gameplayTag = ARGV[5]
local ownerOperationId = ARGV[6]
local ownerFenceEpoch = ARGV[7]
local incarnationDigest = ARGV[8]
local state = ARGV[9]

if schema ~= schemaValue or not uuid(accountId) or not uuid(tenantId) or not uuid(namespaceId)
    or not tag(gameplayTag) or not uuid(ownerOperationId) or not positiveDecimal(ownerFenceEpoch)
    or not digest(incarnationDigest) or state ~= 'CLOSED' then return -2 end
local expectedKey = prefix .. '{' .. gameplayTag .. '}:' .. accountId .. ':' .. namespaceId
if KEYS[1] ~= expectedKey then return -2 end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind == 'none' then
  if redis.call('PTTL', KEYS[1]) ~= -2 then return -1 end
  redis.call('HSET', KEYS[1],
    fields[1], schema,
    fields[2], accountId,
    fields[3], tenantId,
    fields[4], namespaceId,
    fields[5], gameplayTag,
    fields[6], ownerOperationId,
    fields[7], ownerFenceEpoch,
    fields[8], incarnationDigest,
    fields[9], 'CLOSED')
  return 1
end
if kind ~= 'hash' or redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end

local existing = redis.call('HGETALL', KEYS[1])
if #existing ~= 18 then return -1 end
local values = {}
for index = 1, #existing, 2 do
  local field = existing[index]
  local value = existing[index + 1]
  if #field > 32 or #value > maxDecimalBytes then return -1 end
  if values[field] ~= nil then return -1 end
  local known = false
  for fieldIndex = 1, #fields do
    if field == fields[fieldIndex] then known = true break end
  end
  if not known then return -1 end
  values[field] = value
end
for fieldIndex = 1, #fields do
  if values[fields[fieldIndex]] == nil then return -1 end
end

local oldFence = values.ownerFenceEpoch
if values.schema ~= schemaValue or values.accountId ~= accountId or values.tenantId ~= tenantId
    or values.playableStateNamespaceId ~= namespaceId or values.tenantGameplayTag ~= gameplayTag
    or not uuid(values.ownerOperationId)
    or (values.state == 'CLOSED' and not positiveDecimal(oldFence))
    or (values.state == 'OPEN' and not nonnegativeDecimal(oldFence))
    or not digest(values.incarnationDigest)
    or (values.state ~= 'CLOSED' and values.state ~= 'OPEN') then return -1 end

if oldFence == ownerFenceEpoch then
  if values.ownerOperationId == ownerOperationId and values.incarnationDigest == incarnationDigest
      and values.state == 'CLOSED' then return 2 end
  return -3
end
if not decimalLess(oldFence, ownerFenceEpoch) then return -3 end
if values.ownerOperationId == ownerOperationId then return -3 end

redis.call('HSET', KEYS[1],
  fields[1], schema,
  fields[2], accountId,
  fields[3], tenantId,
  fields[4], namespaceId,
  fields[5], gameplayTag,
  fields[6], ownerOperationId,
  fields[7], ownerFenceEpoch,
  fields[8], incarnationDigest,
  fields[9], 'CLOSED')
return 3
