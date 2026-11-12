-- Final original-NEW controller boundary. All seven keys share the persisted tenant slot.
-- FINAL checks both OPEN barriers and replaces only the exact non-admitting session candidate
-- and capacity outcome. FENCE denies future execution without asserting historical non-execution;
-- it writes only the capacity outcome. All three installed tenant indexes remain exact.
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
  'canonicalSessionDurationMillis', 'capacityExecutionDigest'}
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
local hasOrigin = #KEYS == 8 and #ARGV == 22
if not hasOrigin and (#KEYS ~= 7 or #ARGV ~= 19) then return -2 end
local capacityBytes, action, capacityDigest = ARGV[17], ARGV[18], ARGV[19]
if action ~= 'FINAL' and action ~= 'INSTALL' and action ~= 'CLOSE' and action ~= 'FENCE' then return -2 end
local capacity = decode(capacityBytes, 1048576)
if not capacity or capacity.schema ~= 'gs-original-new-capacity-execution/v1'
    or type(capacityDigest) ~= 'string' or #capacityDigest ~= 71
    or not string.match(capacityDigest, '^sha256:[0-9a-f]+$')
    or not uuid(capacity.requestId) or not uuid(capacity.admissionId)
    or not uuid(capacity.admissionAttemptId) or not uuid(capacity.reservationId)
    or type(capacity.selectedOwnerId) ~= 'string' or #capacity.selectedOwnerId < 1 or #capacity.selectedOwnerId > 120
    or not uuid(capacity.transitionId)
    or not positive(capacity.capacityRearmFence) or not positive(capacity.reservationFence)
    or not positive(capacity.selectedOwnerFence) then return -2 end
local capacityExpiry = exactNumber(capacity.expiresAtMillis)
if not capacityExpiry then return -2 end
local capacityNames = {'schema', 'tenantId', 'requestId', 'admissionId', 'admissionAttemptId',
  'reservationId', 'reservationFence', 'capacityRearmFence', 'selectedOwnerId',
  'selectedOwnerFence', 'transitionId', 'expiresAtMillis', 'mutationDigest', 'expiresAt',
  'snapshotIdentity', 'evaluationIdentity', 'candidateDigest', 'canonicalAttemptPayload'}
if not objectWithFields(capacity, capacityNames) then return -2 end
for _, name in ipairs(capacityNames) do
  if type(capacity[name]) ~= 'string' or #capacity[name] == 0 then return -2 end
end
if #capacity.candidateDigest ~= 71
    or not string.match(capacity.candidateDigest, '^sha256:[0-9a-f]+$') then return -2 end
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
    or KEYS[6] ~= prefix .. 'issuer-admission:' .. slot .. ':firemud-account-service:' .. tenantId .. ':' .. namespaceId
    or KEYS[7] ~= prefix .. 'capacity-execution:' .. slot .. ':' .. capacity.admissionAttemptId
    or capacity.tenantId ~= tenantId then return -2 end
-- New receiving-origin executions always carry the eighth same-slot custody key.
-- Historical seven-key executions cannot enter the absent-marker construction branch.
local originClose = false
if hasOrigin then
  if KEYS[8] ~= prefix .. 'capacity-origin:' .. slot .. ':' .. capacity.admissionAttemptId
      or redis.call('TYPE', KEYS[8]).ok ~= 'hash' or redis.call('PTTL', KEYS[8]) ~= -1
      or redis.call('HLEN', KEYS[8]) ~= 4
      or redis.call('HGET', KEYS[8], 'origin') ~= ARGV[20]
      or redis.call('HGET', KEYS[8], 'owner') ~= ARGV[21]
      or redis.call('HGET', KEYS[8], 'fence') ~= ARGV[22] then return -1 end
  local custody = redis.call('HGET', KEYS[8], 'state')
  originClose = action == 'CLOSE' and custody == 'CLOSURE_CLAIMED'
  if not originClose and custody ~= 'DISPATCH_CLAIMED' then return -1 end
end
-- Only a separately qualified current incarnation may dispatch FENCE during normal owner handoff.
-- It excludes future execution, including delayed old callers, but never proves no earlier commit.
if action == 'FENCE' then
  local capacityType = redis.call('TYPE', KEYS[7]).ok
  if capacityType ~= 'none' then
    if capacityType ~= 'hash' or redis.call('PTTL', KEYS[7]) ~= -1
        or redis.call('HGET', KEYS[7], 'binding') ~= capacityBytes
        or redis.call('HGET', KEYS[7], 'digest') ~= capacityDigest then return -1 end
    local state, fields = redis.call('HGET', KEYS[7], 'state'), redis.call('HLEN', KEYS[7])
    if state == 'COMMITTED' then
      local sessionSha1 = redis.call('HGET', KEYS[7], 'sessionSha1')
      local admissionAt = exactNumber(redis.call('HGET', KEYS[7], 'admissionAt'))
      local continuity = exactNumber(redis.call('HGET', KEYS[7], 'continuityExpiresAt'))
      if fields ~= 6 or type(sessionSha1) ~= 'string' or #sessionSha1 ~= 40
          or not string.match(sessionSha1, '^[0-9a-f]+$') or not admissionAt or not continuity
          or continuity <= admissionAt or continuity - admissionAt > 300000 then return -1 end
      return 9
    end
    if fields ~= 3 then return -1 end
    -- Completed exact outcomes are read-only, even after the operation deadline.
    if state == 'FENCED_INDETERMINATE' then return 8 end
    if state == 'CLOSED' then return 7 end
    if state ~= 'AUTHORIZED' then return -1 end
  end
  local fenceTime = redis.call('TIME')
  local fenceSeconds, fenceMicros = tonumber(fenceTime[1]), tonumber(fenceTime[2])
  if not fenceSeconds or not fenceMicros or fenceSeconds < 1 or fenceSeconds ~= math.floor(fenceSeconds)
      or fenceSeconds > math.floor((9007199254740991 - 999) / 1000)
      or fenceMicros < 0 or fenceMicros >= 1000000 or fenceMicros ~= math.floor(fenceMicros) then return -1 end
  local fenceNow = fenceSeconds * 1000 + math.floor(fenceMicros / 1000)
  if fenceNow <= 0 or fenceNow >= deadline then return -1 end
  if capacityType == 'none' then
    redis.call('HSET', KEYS[7], 'binding', capacityBytes, 'digest', capacityDigest,
      'state', 'FENCED_INDETERMINATE')
  else
    redis.call('HSET', KEYS[7], 'state', 'FENCED_INDETERMINATE')
  end
  return 5
end
-- Closure uses retained SQL attempt/candidate identity, not a fresh credential or OPEN grant.
-- It remains available after process restart/credential expiry and never infers from absence.
if action == 'CLOSE' then
  local kind = redis.call('TYPE', KEYS[7]).ok
  local constructing = kind == 'none' and originClose
  if not constructing then
    if kind ~= 'hash' or redis.call('PTTL', KEYS[7]) ~= -1
        or redis.call('HGET', KEYS[7], 'binding') ~= capacityBytes
        or redis.call('HGET', KEYS[7], 'digest') ~= capacityDigest then return -1 end
    local state = redis.call('HGET', KEYS[7], 'state')
    if state == 'CLOSED' and redis.call('HLEN', KEYS[7]) == 3 then return 7 end
    if state == 'COMMITTED' and redis.call('HLEN', KEYS[7]) == 6 then return 9 end
    -- Positive-origin construction must not relabel an already dispatched marker.
    if originClose or state ~= 'AUTHORIZED' or redis.call('HLEN', KEYS[7]) ~= 3 then return -1 end
  end
  if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('PTTL', KEYS[1]) ~= -1
      or redis.call('GET', KEYS[1]) ~= candidateBytes
      or redis.call('TYPE', KEYS[2]).ok ~= 'string' or redis.call('PTTL', KEYS[2]) ~= -1
      or redis.call('GET', KEYS[2]) ~= characterBytes
      or redis.call('TYPE', KEYS[3]).ok ~= 'set' or redis.call('PTTL', KEYS[3]) ~= -1
      or redis.call('SISMEMBER', KEYS[3], sessionId) ~= 1
      or redis.call('TYPE', KEYS[4]).ok ~= 'set' or redis.call('PTTL', KEYS[4]) ~= -1
      or redis.call('SISMEMBER', KEYS[4], sessionId) ~= 1 then return -1 end
  -- Completed replay above is read-only; a first CLOSE mutation must meet this operation's deadline.
  local closeTime = redis.call('TIME')
  local closeSeconds, closeMicros = tonumber(closeTime[1]), tonumber(closeTime[2])
  if not closeSeconds or not closeMicros or closeSeconds < 1 or closeSeconds ~= math.floor(closeSeconds)
      or closeSeconds > math.floor((9007199254740991 - 999) / 1000)
      or closeMicros < 0 or closeMicros >= 1000000 or closeMicros ~= math.floor(closeMicros) then return -1 end
  local closeNow = closeSeconds * 1000 + math.floor(closeMicros / 1000)
  if closeNow <= 0 or closeNow >= deadline then return -1 end
  redis.call('HSET', KEYS[7], 'binding', capacityBytes, 'digest', capacityDigest, 'state', 'CLOSED')
  return 4
end
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
    or template.capacityExecutionDigest ~= capacityDigest
    or not equal(template.originalCandidate, candidate, 0)
    or type(candidate.binding) ~= 'table' or type(candidate.transition) ~= 'table'
    or type(candidate.leaseEvidence) ~= 'table'
    or candidate.binding.accountId ~= accountId or candidate.binding.tenantId ~= tenantId
    or candidate.binding.playableStateNamespaceId ~= namespaceId or candidate.binding.characterId ~= characterId
    or candidate.binding.gameInstanceId ~= gameInstanceId or candidate.binding.sessionId ~= sessionId
    or candidate.binding.issuerId ~= 'firemud-account-service'
    or candidate.transition.bindingGeneration ~= generation
    or candidate.bindingDecisionId ~= capacity.transitionId
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
local capacityType = redis.call('TYPE', KEYS[7]).ok
if action == 'INSTALL' and capacityType == 'none' then
  if now >= capacityExpiry or redis.call('GET', KEYS[1]) ~= candidateBytes then return -1 end
  redis.call('HSET', KEYS[7], 'binding', capacityBytes, 'digest', capacityDigest, 'state', 'AUTHORIZED')
  return 3
end
if capacityType ~= 'hash' or redis.call('PTTL', KEYS[7]) ~= -1
    or redis.call('HGET', KEYS[7], 'binding') ~= capacityBytes
    or redis.call('HGET', KEYS[7], 'digest') ~= capacityDigest then return -1 end
local capacityState = redis.call('HGET', KEYS[7], 'state')
if capacityState == 'CLOSED' then
  if redis.call('HLEN', KEYS[7]) ~= 3 then return -1 end
  return 7
end
if capacityState ~= 'AUTHORIZED' and capacityState ~= 'COMMITTED' then return -1 end
if (capacityState == 'AUTHORIZED' and redis.call('HLEN', KEYS[7]) ~= 3)
    or (capacityState == 'COMMITTED' and redis.call('HLEN', KEYS[7]) ~= 6) then return -1 end
if action == 'INSTALL' then
  if capacityState == 'AUTHORIZED' then return 6 end
  return 9
end
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
  if not equal(finalized, template, 0) or capacityState ~= 'COMMITTED'
      or redis.call('HGET', KEYS[7], 'sessionSha1') ~= redis.sha1hex(observed)
      or redis.call('HGET', KEYS[7], 'admissionAt') ~= string.format('%.0f', admissionAt)
      or redis.call('HGET', KEYS[7], 'continuityExpiresAt') ~= string.format('%.0f', continuity) then return -1 end
  return 2
end
if capacityState ~= 'AUTHORIZED' or now >= capacityExpiry
    or now >= leaseExpiry or now >= expiresAt * 1000
    or issuedAt * 1000 > now + 5000 then return -1 end
local admissionCount, continuityCount
local finalBytes
finalBytes, admissionCount = string.gsub(templateBytes, '"admissionAt":"' .. admissionPlaceholder .. '"',
  '"admissionAt":"' .. string.format('%.0f', now) .. '"')
finalBytes, continuityCount = string.gsub(finalBytes, '"continuityBindingExpiresAt":"' .. continuityPlaceholder .. '"',
  '"continuityBindingExpiresAt":"' .. string.format('%.0f', now + duration) .. '"')
if admissionCount ~= 1 or continuityCount ~= 1 then return -2 end
redis.call('SET', KEYS[1], finalBytes)
redis.call('HSET', KEYS[7], 'state', 'COMMITTED', 'sessionSha1', redis.sha1hex(finalBytes),
  'admissionAt', string.format('%.0f', now), 'continuityExpiresAt', string.format('%.0f', now + duration))
return 1
