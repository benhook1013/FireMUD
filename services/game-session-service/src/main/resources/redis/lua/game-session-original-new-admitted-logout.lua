-- Tenant-slot part of admitted original NEW logout. The caller must hold the exact
-- selected incarnation and a durable logout intent, and independently confirm the postimage.
-- This script supplies no Account retirement, region cleanup, or durable SQL receipt.
local function uuid(value)
  return type(value) == 'string' and #value == 36 and value == string.lower(value)
    and value ~= '00000000-0000-0000-0000-000000000000'
    and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
end

local function positive(value, maximumLength)
  return type(value) == 'string' and #value <= maximumLength
    and string.match(value, '^[1-9][0-9]*$') ~= nil
end

local function decode(bytes)
  if type(bytes) ~= 'string' or #bytes == 0 or #bytes > 1048576 then return nil end
  local ok, value = pcall(cjson.decode, bytes)
  if not ok or type(value) ~= 'table' then return nil end
  return value
end

if #KEYS ~= 5 or #ARGV ~= 14 then return -2 end
local tenantId, accountId, namespaceId, characterId = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
local gameInstanceId, sessionId, generation, tag = ARGV[5], ARGV[6], ARGV[7], ARGV[8]
local executionDigest, logoutId, deadlineText = ARGV[9], ARGV[10], ARGV[11]
local executionBytes, sessionSha1 = ARGV[12], ARGV[13]
local action = ARGV[14]
if not uuid(tenantId) or not uuid(accountId) or not uuid(namespaceId) or not uuid(characterId)
    or not uuid(gameInstanceId) or not uuid(sessionId) or not uuid(logoutId)
    or not positive(generation, 256) or #tag > 44
    or not string.match(tag, '^g[1-9][0-9]*%-[0-9a-f]+$')
    or #executionDigest ~= 71 or not string.match(executionDigest, '^sha256:[0-9a-f]+$')
    or #sessionSha1 ~= 40 or not string.match(sessionSha1, '^[0-9a-f]+$')
    or not positive(deadlineText, 16)
    or (action ~= 'DISPATCH' and action ~= 'OBSERVE') then return -2 end
local deadline = tonumber(deadlineText)
if not deadline or deadline > 9007199254740991
    or string.format('%.0f', deadline) ~= deadlineText then return -2 end

local prefix = 'session:game:'
local slot = '{' .. tag .. '}'
if KEYS[1] ~= prefix .. slot .. ':' .. gameInstanceId .. ':' .. sessionId
    or KEYS[2] ~= prefix .. 'index:character:' .. slot .. ':' .. namespaceId .. ':' .. characterId
    or KEYS[3] ~= prefix .. 'index:account-tenant:' .. slot .. ':' .. accountId
    or KEYS[4] ~= prefix .. 'index:tenant:' .. slot then return -2 end

-- The admitted marker is the original immutable execution witness, never logout state.
if redis.call('TYPE', KEYS[5]).ok ~= 'hash' or redis.call('PTTL', KEYS[5]) ~= -1
    or redis.call('HLEN', KEYS[5]) ~= 6
    or redis.call('HGET', KEYS[5], 'state') ~= 'COMMITTED'
    or redis.call('HGET', KEYS[5], 'digest') ~= executionDigest
    or redis.call('HGET', KEYS[5], 'binding') ~= executionBytes
    or redis.call('HGET', KEYS[5], 'sessionSha1') ~= sessionSha1 then return -1 end
local execution = decode(executionBytes)
if not execution or execution.schema ~= 'gs-original-new-capacity-execution/v1'
    or execution.tenantId ~= tenantId or not uuid(execution.admissionAttemptId)
    or KEYS[5] ~= prefix .. 'capacity-execution:' .. slot .. ':' .. execution.admissionAttemptId then
  return -1
end
local admissionAt = redis.call('HGET', KEYS[5], 'admissionAt')
local continuityExpiresAt = redis.call('HGET', KEYS[5], 'continuityExpiresAt')
if not positive(admissionAt, 16) or not positive(continuityExpiresAt, 16) then return -1 end

if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('PTTL', KEYS[1]) ~= -1 then
  return -1
end
local session = redis.call('GET', KEYS[1])
local characterType = redis.call('TYPE', KEYS[2]).ok
if characterType ~= 'none'
    and (characterType ~= 'string' or redis.call('PTTL', KEYS[2]) ~= -1) then return -1 end
local character = redis.call('GET', KEYS[2])
local characterExpected = '{"sessionId":"' .. sessionId .. '","gameInstanceId":"' .. gameInstanceId
  .. '","bindingGeneration":"' .. generation .. '"}'
local function membership(key)
  local kind = redis.call('TYPE', key).ok
  if kind == 'none' then return 0 end
  if kind ~= 'set' or redis.call('PTTL', key) ~= -1 then return nil end
  return redis.call('SISMEMBER', key, sessionId)
end
local accountMember, tenantMember = membership(KEYS[3]), membership(KEYS[4])
local tombstone = '{"purpose":"original-new-admitted-logout/v1","bindingGeneration":"' .. generation
  .. '","capacityExecutionDigest":"' .. executionDigest .. '","logoutId":"' .. logoutId
  .. '","originalSessionSha1":"' .. sessionSha1 .. '"}'

if session == tombstone then
  if accountMember ~= 0 or tenantMember ~= 0 then return -1 end
  if character then
    -- An exact readback may coexist with a successor, whose pointer must remain intact.
    local next = decode(character)
    if not next or not uuid(next.sessionId) or next.sessionId == sessionId
        or not uuid(next.gameInstanceId) or not positive(next.bindingGeneration, 256)
        or #next.bindingGeneration < #generation
        or (#next.bindingGeneration == #generation and next.bindingGeneration <= generation)
        or character ~= '{"sessionId":"' .. next.sessionId .. '","gameInstanceId":"'
            .. next.gameInstanceId .. '","bindingGeneration":"' .. next.bindingGeneration .. '"}' then
      return -1
    end
  end
  return 2
end
-- Recovery uses EVALSHA_RO. A live value or absent marker cannot prove a completed fence.
if action == 'OBSERVE' then return -1 end
if redis.sha1hex(session) ~= sessionSha1 or character ~= characterExpected
    or accountMember ~= 1 or tenantMember ~= 1 then return -1 end
local admitted = decode(session)
if not admitted or admitted.schemaVersion ~= 2 or admitted.admissionState ~= 'COMPLETE'
    or admitted.bindingGeneration ~= generation or admitted.capacityExecutionDigest ~= executionDigest
    or admitted.admissionAt ~= admissionAt or admitted.continuityBindingExpiresAt ~= continuityExpiresAt then
  return -1
end

local time = redis.call('TIME')
local seconds, micros = tonumber(time[1]), tonumber(time[2])
if not seconds or not micros or seconds < 1 or seconds > math.floor((9007199254740991 - 999) / 1000)
    or seconds ~= math.floor(seconds) or micros < 0 or micros >= 1000000
    or micros ~= math.floor(micros) then return -1 end
local now = seconds * 1000 + math.floor(micros / 1000)
if now >= deadline then return -1 end
if not redis.acl_check_cmd('SET', KEYS[1], tombstone)
    or not redis.acl_check_cmd('DEL', KEYS[2])
    or not redis.acl_check_cmd('SREM', KEYS[3], sessionId)
    or not redis.acl_check_cmd('SREM', KEYS[4], sessionId) then return -1 end

-- Replacing the serving key prevents same-binding restoration from treating absence as free.
-- The pointer and memberships are removed only after the exact current generation matches.
redis.call('SET', KEYS[1], tombstone)
redis.call('DEL', KEYS[2])
redis.call('SREM', KEYS[3], sessionId)
redis.call('SREM', KEYS[4], sessionId)
return 1
