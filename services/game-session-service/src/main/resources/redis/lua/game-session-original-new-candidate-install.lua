-- First publication of one exact ORIGINAL_NEW non-admitting candidate.
-- KEYS: session, character index, account-tenant set, tenant set.
-- This script has no replay/create mode: the SQL owner authorizes exactly one invocation.
local sessionPrefix = 'session:game:'
local characterPrefix = 'session:game:index:character:'
local accountTenantPrefix = 'session:game:index:account-tenant:'
local tenantPrefix = 'session:game:index:tenant:'
local maxCandidateBytes = 524288
local maxIdentifierBytes = 512
local maxGenerationBytes = 256

local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
      and value ~= '00000000-0000-0000-0000-000000000000'
end

local function positive(value)
  return type(value) == 'string' and #value > 0 and #value <= maxGenerationBytes
      and string.match(value, '^[1-9][0-9]*$')
end

local function boundedText(value)
  return type(value) == 'string' and #value > 0 and #value <= maxIdentifierBytes
end

if #KEYS ~= 4 or #ARGV ~= 10 then return -2 end
local purpose = ARGV[1]
local tenantId = ARGV[2]
local accountId = ARGV[3]
local namespaceId = ARGV[4]
local characterId = ARGV[5]
local gameInstanceId = ARGV[6]
local sessionId = ARGV[7]
local bindingGeneration = ARGV[8]
local tenantGameplayTag = ARGV[9]
local candidateBytes = ARGV[10]

if purpose ~= 'original-new-non-admitting-candidate/v1'
    or not uuid(tenantId) or not uuid(accountId) or not uuid(namespaceId)
    or not uuid(characterId) or not uuid(gameInstanceId) or not uuid(sessionId)
    or not positive(bindingGeneration) or not boundedText(tenantGameplayTag)
    or type(candidateBytes) ~= 'string' or #candidateBytes == 0 or #candidateBytes > maxCandidateBytes then
  return -2
end

local sessionKey = sessionPrefix .. '{' .. tenantGameplayTag .. '}:' .. gameInstanceId .. ':' .. sessionId
local characterKey = characterPrefix .. '{' .. tenantGameplayTag .. '}:' .. namespaceId .. ':' .. characterId
local accountTenantKey = accountTenantPrefix .. '{' .. tenantGameplayTag .. '}:' .. accountId
local tenantKey = tenantPrefix .. '{' .. tenantGameplayTag .. '}'
if KEYS[1] ~= sessionKey or KEYS[2] ~= characterKey
    or KEYS[3] ~= accountTenantKey or KEYS[4] ~= tenantKey then return -2 end

-- Check every key before the first mutation. The session and character projection must be truly
-- absent; existing exact values are still an unknown prior writer and are never adopted.
local sessionType = redis.call('TYPE', KEYS[1]).ok
local characterType = redis.call('TYPE', KEYS[2]).ok
local accountType = redis.call('TYPE', KEYS[3]).ok
local tenantType = redis.call('TYPE', KEYS[4]).ok
if sessionType ~= 'none' or characterType ~= 'none'
    or (accountType ~= 'none' and accountType ~= 'set')
    or (tenantType ~= 'none' and tenantType ~= 'set') then return -1 end
if redis.call('PTTL', KEYS[1]) ~= -2 or redis.call('PTTL', KEYS[2]) ~= -2
    or (accountType == 'none' and redis.call('PTTL', KEYS[3]) ~= -2)
    or (accountType == 'set' and redis.call('PTTL', KEYS[3]) ~= -1)
    or (tenantType == 'none' and redis.call('PTTL', KEYS[4]) ~= -2)
    or (tenantType == 'set' and redis.call('PTTL', KEYS[4]) ~= -1) then return -1 end
if redis.call('SISMEMBER', KEYS[3], sessionId) ~= 0
    or redis.call('SISMEMBER', KEYS[4], sessionId) ~= 0 then return -1 end

-- These writes are additive and target only the four exact same-slot keys.
redis.call('SET', KEYS[1], candidateBytes)
redis.call('SET', KEYS[2],
    '{"sessionId":"' .. sessionId .. '","gameInstanceId":"' .. gameInstanceId
      .. '","bindingGeneration":"' .. bindingGeneration .. '"}')
redis.call('SADD', KEYS[3], sessionId)
redis.call('SADD', KEYS[4], sessionId)
return 1
