-- Create the one INITIAL OPEN account barrier at its exact captured NO_ACTIVE control epoch.
-- This script is create-only. Retries after a possible dispatch must verify by readback instead.
local prefix = 'session:game:account-admission:'
local fields = {
  'schema', 'accountId', 'tenantId', 'playableStateNamespaceId', 'tenantGameplayTag',
  'ownerOperationId', 'ownerFenceEpoch', 'incarnationDigest', 'state'
}
local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
      and value ~= '00000000-0000-0000-0000-000000000000'
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
local function epoch(value)
  if type(value) ~= 'string' or #value == 0 or #value > 256 then return false end
  return value == '0' or string.match(value, '^[1-9][0-9]*$') ~= nil
end

if #KEYS ~= 1 or #ARGV ~= 9 then return -2 end
local schema = ARGV[1]
local accountId = ARGV[2]
local tenantId = ARGV[3]
local namespaceId = ARGV[4]
local gameplayTag = ARGV[5]
local initializerId = ARGV[6]
local ownerFenceEpoch = ARGV[7]
local incarnationDigest = ARGV[8]
local state = ARGV[9]
if schema ~= 'gs_account_admission_barrier/v1'
    or not uuid(accountId) or not uuid(tenantId) or not uuid(namespaceId)
    or not tag(gameplayTag) or not uuid(initializerId) or not epoch(ownerFenceEpoch)
    or not digest(incarnationDigest) or state ~= 'OPEN' then return -2 end
local expectedKey = prefix .. '{' .. gameplayTag .. '}:' .. accountId .. ':' .. namespaceId
if KEYS[1] ~= expectedKey then return -2 end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' or redis.call('PTTL', KEYS[1]) ~= -2 then return 0 end
redis.call('HSET', KEYS[1],
  fields[1], schema,
  fields[2], accountId,
  fields[3], tenantId,
  fields[4], namespaceId,
  fields[5], gameplayTag,
  fields[6], initializerId,
  fields[7], ownerFenceEpoch,
  fields[8], incarnationDigest,
  fields[9], 'OPEN')
return 1
