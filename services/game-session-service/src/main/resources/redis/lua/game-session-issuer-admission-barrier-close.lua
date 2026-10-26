-- Install one exact SQL-prepared CLOSED issuer barrier for a selected-A cutoff target.
-- One tenant-slot key only; only the exact captured OPEN initializer can be replaced.
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

local function decimalLess(left, right)
  if #left ~= #right then return #left < #right end
  return left < right
end

if #KEYS ~= 1 or #ARGV ~= 14 then return -2 end
local issuerId = ARGV[1]
local tenantId = ARGV[2]
local namespaceId = ARGV[3]
local gameplayTag = ARGV[4]
local cutoffOperationId = ARGV[5]
local cutoffEpoch = ARGV[6]
local cutoffPayloadDigest = ARGV[7]
local cutoffPayload = ARGV[8]
local initializerOperationId = ARGV[9]
local initializerEpoch = ARGV[10]
local initializerDigest = ARGV[11]
local expectedOpenPayloadDigest = ARGV[12]
local expectedOpenPayload = ARGV[13]
local state = ARGV[14]
local version, opaque
if type(gameplayTag) == 'string' then
  version, opaque = string.match(gameplayTag, '^g([1-9][0-9]*)%-([0-9a-f]+)$')
end
if issuerId ~= 'firemud-account-service' or not uuid(tenantId) or not uuid(namespaceId)
    or version == nil or #version > 10 or opaque == nil or #opaque ~= 32 or #gameplayTag > 44
    or not uuid(cutoffOperationId) or not positive(cutoffEpoch)
    or not digest(cutoffPayloadDigest) or type(cutoffPayload) ~= 'string'
    or #cutoffPayload == 0 or #cutoffPayload > maxValueBytes
    or (initializerOperationId ~= 'none' and not uuid(initializerOperationId))
    or (initializerEpoch ~= '0' and not positive(initializerEpoch))
    or (initializerDigest ~= 'none' and not digest(initializerDigest))
    or (expectedOpenPayloadDigest ~= 'none' and not digest(expectedOpenPayloadDigest))
    or type(expectedOpenPayload) ~= 'string' or #expectedOpenPayload > maxValueBytes
    or state ~= 'CLOSED' then return -2 end
if initializerOperationId == 'none' then
  if initializerEpoch ~= '0' or initializerDigest ~= 'none'
      or expectedOpenPayloadDigest ~= 'none' or #expectedOpenPayload ~= 0 then return -2 end
else
  if initializerEpoch == '0' or initializerDigest == 'none'
      or expectedOpenPayloadDigest == 'none' or #expectedOpenPayload == 0
      or not decimalLess(initializerEpoch, cutoffEpoch) then return -2 end
end
local expectedKey = prefix .. '{' .. gameplayTag .. '}:' .. issuerId .. ':' .. tenantId .. ':' .. namespaceId
if KEYS[1] ~= expectedKey then return -2 end

local function writeClosed()
  redis.call('HSET', KEYS[1],
    fields[1], schemaValue,
    fields[2], issuerId,
    fields[3], tenantId,
    fields[4], namespaceId,
    fields[5], gameplayTag,
    fields[6], 'CLOSED',
    fields[7], cutoffEpoch,
    fields[8], cutoffOperationId,
    fields[9], initializerOperationId,
    fields[10], initializerEpoch,
    fields[11], initializerDigest,
    fields[12], cutoffPayloadDigest,
    fields[13], cutoffPayload)
end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind == 'none' then
  if redis.call('PTTL', KEYS[1]) ~= -2 then return -1 end
  writeClosed()
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
    or values.playableStateNamespaceId ~= namespaceId or values.tenantGameplayTag ~= gameplayTag then return -1 end
if values.state == 'CLOSED' then
  if values.admissionEpoch == cutoffEpoch and values.ownerOperationId == cutoffOperationId
      and values.initializerOperationId == initializerOperationId
      and values.initializerEpoch == initializerEpoch and values.initializerDigest == initializerDigest
      and values.payloadDigest == cutoffPayloadDigest and values.payload == cutoffPayload then return 2 end
  return -3
end
if values.state ~= 'OPEN' or initializerOperationId == 'none'
    or values.admissionEpoch ~= initializerEpoch or values.ownerOperationId ~= initializerOperationId
    or values.initializerOperationId ~= initializerOperationId
    or values.initializerEpoch ~= initializerEpoch or values.initializerDigest ~= initializerDigest
    or values.payloadDigest ~= expectedOpenPayloadDigest or values.payload ~= expectedOpenPayload
    or not decimalLess(values.admissionEpoch, cutoffEpoch) then return -3 end
writeClosed()
return 3
