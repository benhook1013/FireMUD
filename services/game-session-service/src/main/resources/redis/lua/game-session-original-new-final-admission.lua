-- Final original-NEW controller boundary. All six keys share the persisted tenant slot.
-- The two OPEN barriers are checked only. The only write replaces the exact non-admitting
-- session candidate; all three already-installed tenant indexes must remain exact and persistent.
local admissionPlaceholder = '__ORIGINAL_NEW_ADMISSION_AT_MS__'
local continuityPlaceholder = '__ORIGINAL_NEW_CONTINUITY_EXPIRES_AT_MS__'
local accountNames = {'schema', 'accountId', 'tenantId', 'playableStateNamespaceId',
  'tenantGameplayTag', 'ownerOperationId', 'ownerFenceEpoch', 'incarnationDigest', 'state'}
local issuerNames = {'schema', 'issuerId', 'tenantId', 'playableStateNamespaceId',
  'tenantGameplayTag', 'state', 'admissionEpoch', 'ownerOperationId', 'initializerOperationId',
  'initializerEpoch', 'initializerDigest', 'payloadDigest', 'payload'}
local issuerMetadataNames = {'schema', 'issuerId', 'tenantId', 'playableStateNamespaceId',
  'tenantGameplayTag', 'state', 'admissionEpoch', 'ownerOperationId', 'initializerOperationId',
  'initializerEpoch', 'initializerDigest', 'payloadDigest'}
local sessionNames = {'schemaVersion', 'admissionState', 'originalCandidate', 'bindingGeneration',
  'accountIndexState', 'issuerIndexState', 'regionBridgeState', 'admissionAt', 'authTokenHash',
  'authTokenIssuedAt', 'authTokenExpiresAt', 'tokenGeneration', 'issuanceFence', 'rebindHandle',
  'continuityBindingExpiresAt', 'membershipBaseline', 'authorityTuple', 'outboxCheckpoints',
  'canonicalSessionDurationMillis'}
local function uuid(value)
  return type(value) == 'string' and #value == 36 and value == string.lower(value)
    and value ~= '00000000-0000-0000-0000-000000000000'
    and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
end
local function positive(value)
  return type(value) == 'string' and #value > 0 and #value <= 256
    and string.match(value, '^[1-9][0-9]*$') ~= nil
end
local function exactNumber(value)
  if not positive(value) or #value > 16 then return nil end
  local number = tonumber(value)
  if not number or number > 9007199254740991 or string.format('%.0f', number) ~= value then return nil end
  return number
end
local function objectWithFields(value, fields)
  if type(value) ~= 'table' then return false end
  local count = 0
  for key, item in pairs(value) do
    count = count + 1
    local found = false
    for _, name in ipairs(fields) do if key == name then found = true end end
    if not found or item == cjson.null then return false end
  end
  if count ~= #fields then return false end
  for _, name in ipairs(fields) do if value[name] == nil then return false end end
  return true
end
local function equal(left, right, depth)
  if depth > 64 or type(left) ~= type(right) then return false end
  if type(left) ~= 'table' then return left == right end
  local countLeft, countRight = 0, 0
  for key, value in pairs(left) do
    countLeft = countLeft + 1
    if not equal(value, right[key], depth + 1) then return false end
  end
  for _ in pairs(right) do countRight = countRight + 1 end
  return countLeft == countRight
end
local function decode(bytes, maximum)
  if type(bytes) ~= 'string' or #bytes == 0 or #bytes > maximum then return nil end
  local ok, value = pcall(cjson.decode, bytes)
  if not ok or type(value) ~= 'table' then return nil end
  return value
end
if #KEYS ~= 6 or #ARGV ~= 16 then return -2 end
local tenantId, accountId, namespaceId, characterId = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
local gameInstanceId, sessionId, generation, tag = ARGV[5], ARGV[6], ARGV[7], ARGV[8]
local candidateBytes, characterBytes, templateBytes = ARGV[9], ARGV[10], ARGV[11]
local duration, deadline = exactNumber(ARGV[12]), exactNumber(ARGV[15])
if not uuid(tenantId) or not uuid(accountId) or not uuid(namespaceId) or not uuid(characterId)
    or not uuid(gameInstanceId) or not uuid(sessionId) or not positive(generation)
    or type(tag) ~= 'string' or #tag > 44
    or not string.match(tag, '^g[1-9][0-9]*%-[0-9a-f]+$')
    or not duration or duration > 300000 or not deadline then return -2 end
if type(ARGV[16]) ~= 'string' or #ARGV[16] == 0 or #ARGV[16] > 524288 then return -2 end
local prefix = 'session:game:'
local slot = '{' .. tag .. '}'
if KEYS[1] ~= prefix .. slot .. ':' .. gameInstanceId .. ':' .. sessionId
    or KEYS[2] ~= prefix .. 'index:character:' .. slot .. ':' .. namespaceId .. ':' .. characterId
    or KEYS[3] ~= prefix .. 'index:account-tenant:' .. slot .. ':' .. accountId
    or KEYS[4] ~= prefix .. 'index:tenant:' .. slot
    or KEYS[5] ~= prefix .. 'account-admission:' .. slot .. ':' .. accountId .. ':' .. namespaceId
    or KEYS[6] ~= prefix .. 'issuer-admission:' .. slot .. ':firemud-account-service:' .. tenantId .. ':' .. namespaceId then return -2 end
local candidate = decode(candidateBytes, 524288)
local template = decode(templateBytes, 1048576)
local account = decode(ARGV[13], 16384)
local issuer = decode(ARGV[14], 1048576)
if not candidate or candidate.purpose ~= 'original-new-non-admitting-candidate/v1'
    or not objectWithFields(template, sessionNames)
    or not objectWithFields(account, accountNames)
    or not objectWithFields(issuer, issuerMetadataNames)
    or template.schemaVersion ~= 2 or template.admissionState ~= 'COMPLETE'
    or template.admissionAt ~= admissionPlaceholder or template.continuityBindingExpiresAt ~= continuityPlaceholder
    or template.bindingGeneration ~= generation or template.tokenGeneration ~= '1'
    or template.accountIndexState ~= 'ACKNOWLEDGED' or template.issuerIndexState ~= 'ACKNOWLEDGED'
    or template.regionBridgeState ~= 'ACKNOWLEDGED'
    or template.canonicalSessionDurationMillis ~= ARGV[12]
    or not equal(template.originalCandidate, candidate, 0)
    or type(candidate.binding) ~= 'table' or type(candidate.transition) ~= 'table'
    or type(candidate.leaseEvidence) ~= 'table'
    or candidate.binding.accountId ~= accountId or candidate.binding.tenantId ~= tenantId
    or candidate.binding.playableStateNamespaceId ~= namespaceId or candidate.binding.characterId ~= characterId
    or candidate.binding.gameInstanceId ~= gameInstanceId or candidate.binding.sessionId ~= sessionId
    or candidate.binding.issuerId ~= 'firemud-account-service'
    or candidate.transition.bindingGeneration ~= generation
    or not equal(template.authorityTuple, candidate.leaseEvidence.authorityTuple, 0)
    or not equal(template.membershipBaseline, candidate.leaseEvidence.membershipBaseline, 0)
    or not equal(template.outboxCheckpoints, candidate.leaseEvidence.outboxCheckpoints, 0)
    or template.issuanceFence ~= candidate.leaseEvidence.issuanceFence
    or type(template.authTokenHash) ~= 'string' or #template.authTokenHash ~= 64
    or not string.match(template.authTokenHash, '^[0-9a-f]+$')
    or type(template.rebindHandle) ~= 'string' or #template.rebindHandle ~= 43
    or not string.match(template.rebindHandle, '^[A-Za-z0-9_-]+$')
    or account.state ~= 'OPEN' or account.schema ~= 'gs_account_admission_barrier/v1'
    or account.accountId ~= accountId or account.tenantId ~= tenantId
    or account.playableStateNamespaceId ~= namespaceId or account.tenantGameplayTag ~= tag
    or issuer.state ~= 'OPEN' or issuer.schema ~= 'gs_issuer_admission_barrier/v1'
    or issuer.issuerId ~= 'firemud-account-service' or issuer.tenantId ~= tenantId
    or issuer.playableStateNamespaceId ~= namespaceId or issuer.tenantGameplayTag ~= tag then return -2 end
issuer.payload = ARGV[16]
local issuedAt, expiresAt = exactNumber(template.authTokenIssuedAt), exactNumber(template.authTokenExpiresAt)
local leaseExpiry = exactNumber(candidate.leaseEvidence.expiresAt)
local maximumExactSeconds = math.floor(9007199254740991 / 1000)
if not issuedAt or not expiresAt or expiresAt <= issuedAt
    or issuedAt > maximumExactSeconds or expiresAt > maximumExactSeconds or not leaseExpiry then return -2 end
local expectedCharacter = '{"sessionId":"' .. sessionId .. '","gameInstanceId":"' .. gameInstanceId
  .. '","bindingGeneration":"' .. generation .. '"}'
if characterBytes ~= expectedCharacter then return -2 end
local function exactBarrier(key, expected, names)
  if redis.call('TYPE', key).ok ~= 'hash' or redis.call('PTTL', key) ~= -1
      or redis.call('HLEN', key) ~= #names then return false end
  for _, name in ipairs(names) do
    if type(expected[name]) ~= 'string' or redis.call('HGET', key, name) ~= expected[name] then return false end
  end
  return true
end
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('PTTL', KEYS[1]) ~= -1
    or redis.call('TYPE', KEYS[2]).ok ~= 'string' or redis.call('PTTL', KEYS[2]) ~= -1
    or redis.call('GET', KEYS[2]) ~= characterBytes
    or redis.call('TYPE', KEYS[3]).ok ~= 'set' or redis.call('PTTL', KEYS[3]) ~= -1
    or redis.call('SISMEMBER', KEYS[3], sessionId) ~= 1
    or redis.call('TYPE', KEYS[4]).ok ~= 'set' or redis.call('PTTL', KEYS[4]) ~= -1
    or redis.call('SISMEMBER', KEYS[4], sessionId) ~= 1
    or not exactBarrier(KEYS[5], account, accountNames)
    or not exactBarrier(KEYS[6], issuer, issuerNames) then return -1 end
local time = redis.call('TIME')
local seconds, micros = tonumber(time[1]), tonumber(time[2])
if not seconds or not micros or seconds < 1 or seconds ~= math.floor(seconds)
    or seconds > math.floor((9007199254740991 - 999) / 1000)
    or micros < 0 or micros >= 1000000 or micros ~= math.floor(micros) then return -1 end
local now = seconds * 1000 + math.floor(micros / 1000)
if now <= 0 or now >= deadline or now > 9007199254740991 - math.max(duration, 5000) then return -1 end
local observed = redis.call('GET', KEYS[1])
if observed ~= candidateBytes then
  -- Exact replay is read-only and retains the original controller times.
  local finalized = decode(observed, 1048576)
  if not objectWithFields(finalized, sessionNames) then return -1 end
  local admissionAt = exactNumber(finalized.admissionAt)
  local continuity = exactNumber(finalized.continuityBindingExpiresAt)
  if not admissionAt or admissionAt > 9007199254740991 - duration or not continuity
      or continuity ~= admissionAt + duration then return -1 end
  finalized.admissionAt, finalized.continuityBindingExpiresAt = admissionPlaceholder, continuityPlaceholder
  if not equal(finalized, template, 0) then return -1 end
  return 2
end
if now >= leaseExpiry or now >= expiresAt * 1000
    or issuedAt * 1000 > now + 5000 then return -1 end
local admissionCount, continuityCount
local finalBytes
finalBytes, admissionCount = string.gsub(templateBytes, '"admissionAt":"' .. admissionPlaceholder .. '"',
  '"admissionAt":"' .. string.format('%.0f', now) .. '"')
finalBytes, continuityCount = string.gsub(finalBytes, '"continuityBindingExpiresAt":"' .. continuityPlaceholder .. '"',
  '"continuityBindingExpiresAt":"' .. string.format('%.0f', now + duration) .. '"')
if admissionCount ~= 1 or continuityCount ~= 1 then return -2 end
redis.call('SET', KEYS[1], finalBytes)
return 1
