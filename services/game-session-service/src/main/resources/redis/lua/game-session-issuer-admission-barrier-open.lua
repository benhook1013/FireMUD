-- Install one exact SQL-prepared initial OPEN issuer admission barrier.
-- This one tenant-slot key only; absence is usable only for this exact original initializer.
local prefix = 'session:game:issuer-admission:'
local schemaValue = 'gs_issuer_admission_barrier/v1'
local fields = {
  'schema', 'issuerId', 'tenantId', 'playableStateNamespaceId', 'tenantGameplayTag',
  'state', 'admissionEpoch', 'ownerOperationId', 'initializerOperationId',
  'initializerEpoch', 'initializerDigest', 'payloadDigest', 'payload'
}
local maxValueBytes = 524288
local maxDecimalBytes = 256

local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
      and value ~= '00000000-0000-0000-0000-000000000000'
end

local function positive(value)
  return type(value) == 'string' and #value > 0 and #value <= maxDecimalBytes
      and string.match(value, '^[1-9][0-9]*$') ~= nil
end

local function digest(value)
  return type(value) == 'string' and #value == 71
      and string.match(value, '^sha256:[0-9a-f]+$') ~= nil
end

local function tag(value)
  if type(value) ~= 'string' or #value > 44 then return false end
  local version, opaque = string.match(value, '^g([1-9][0-9]*)%-([0-9a-f]+)$')
  return version ~= nil and #version <= 10 and opaque ~= nil and #opaque == 32
end

if #KEYS ~= 1 or #ARGV ~= 11 then return -2 end
local issuerId = ARGV[1]
local tenantId = ARGV[2]
local namespaceId = ARGV[3]
local gameplayTag = ARGV[4]
local operationId = ARGV[5]
local admissionEpoch = ARGV[6]
local initializerDigest = ARGV[7]
local payloadDigest = ARGV[8]
local payload = ARGV[9]
local state = ARGV[10]
local createMode = ARGV[11]
if issuerId ~= 'firemud-account-service' or not uuid(tenantId) or not uuid(namespaceId)
    or not tag(gameplayTag)
    or not uuid(operationId) or not positive(admissionEpoch)
    or not digest(initializerDigest) or not digest(payloadDigest)
    or type(payload) ~= 'string' or #payload == 0 or #payload > maxValueBytes
    or state ~= 'OPEN'
    or (createMode ~= 'CREATE_ONCE' and createMode ~= 'REPLAY_ONLY') then return -2 end
local expectedKey = prefix .. '{' .. gameplayTag .. '}:' .. issuerId .. ':' .. tenantId .. ':' .. namespaceId
if KEYS[1] ~= expectedKey then return -2 end

local function writeOpen()
  redis.call('HSET', KEYS[1],
    fields[1], schemaValue,
    fields[2], issuerId,
    fields[3], tenantId,
    fields[4], namespaceId,
    fields[5], gameplayTag,
    fields[6], 'OPEN',
    fields[7], admissionEpoch,
    fields[8], operationId,
    fields[9], operationId,
    fields[10], admissionEpoch,
    fields[11], initializerDigest,
    fields[12], payloadDigest,
    fields[13], payload)
end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind == 'none' then
  if redis.call('PTTL', KEYS[1]) ~= -2 then return -1 end
  if createMode ~= 'CREATE_ONCE' then return -1 end
  writeOpen()
  return 1
end
if kind ~= 'hash' or redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end
local existing = redis.call('HGETALL', KEYS[1])
if #existing ~= 2 * #fields then return -1 end
local values = {}
for index = 1, #existing, 2 do
  local field = existing[index]
  local value = existing[index + 1]
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
if values.schema ~= schemaValue or values.issuerId ~= issuerId or values.tenantId ~= tenantId
    or values.playableStateNamespaceId ~= namespaceId or values.tenantGameplayTag ~= gameplayTag
    or values.state ~= 'OPEN' or values.admissionEpoch ~= admissionEpoch
    or values.ownerOperationId ~= operationId or values.initializerOperationId ~= operationId
    or values.initializerEpoch ~= admissionEpoch or values.initializerDigest ~= initializerDigest
    or values.payloadDigest ~= payloadDigest or values.payload ~= payload then return -3 end
return 2
