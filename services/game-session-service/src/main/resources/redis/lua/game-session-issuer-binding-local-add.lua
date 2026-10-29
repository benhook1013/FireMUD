-- Project one SQL-registered ordinary issuer reservation and, only after its V29 phase
-- qualifies, its binding member. KEYS[1] is the only issuer partition hash.
local keyPrefix = 'session:game:index:issuer:'
local metadataField = '!issuer-partition-bootstrap/v1'
local mutationRevisionField = '!issuer-partition-mutation-revision/v1'
local capacityPrefix = '!issuer-reservation-capacity/v1:'
local maxDecimalBytes = 256
local maxBindingRefBytes = 6144
local maxMemberValueBytes = 65536
local maxMetadataBytes = 65536
local maxMemberOperations = 512
local maxHashFields = 1025
local maxPhysicalFields = 1026
local maxBaselineFields = 513
local maxHashBytes = 16777216

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

local function nonnegative(value)
  return value == '0' or positive(value)
end

local function digest(value)
  return type(value) == 'string' and #value == 71
      and string.match(value, '^sha256:[0-9a-f]+$') ~= nil
end

local function greaterDecimal(left, right)
  if #left ~= #right then return #left > #right end
  return left > right
end

local function readSegment(bytes, offset)
  local start = offset
  while offset <= #bytes do
    local char = string.byte(bytes, offset)
    if char < 48 or char > 57 then break end
    offset = offset + 1
  end
  if offset == start or offset > #bytes or string.byte(bytes, offset) ~= 58 then return nil end
  if offset - start > 1 and string.byte(bytes, start) == 48 then return nil end
  local lengthText = string.sub(bytes, start, offset - 1)
  if #lengthText > 5 then return nil end
  local length = tonumber(lengthText)
  if not length or length > maxBindingRefBytes or offset + length > #bytes then return nil end
  local valueStart = offset + 1
  local valueEnd = offset + length
  return string.sub(bytes, valueStart, valueEnd), valueEnd + 1
end

local function canonicalBindingRef(bytes, member)
  if type(bytes) ~= 'string' or #bytes == 0 or #bytes > maxBindingRefBytes then return false end
  local domain, offset = readSegment(bytes, 1)
  if domain ~= 'bindingRef/v1' or not offset then return false end
  local tenant, afterTenant = readSegment(bytes, offset)
  if not tenant or not afterTenant then return false end
  local instance, afterInstance = readSegment(bytes, afterTenant)
  if not instance or not afterInstance then return false end
  local session, afterSession = readSegment(bytes, afterInstance)
  if not tenant or not instance or not session or not afterSession or afterSession ~= #bytes + 1 then
    return false
  end
  return uuid(tenant) and uuid(instance) and uuid(session)
      and tenant == member.tenantId and instance == member.gameInstanceId and session == member.sessionId
end

local function sameIncarnation(left, right)
  if type(left) ~= 'table' or type(right) ~= 'table'
      or left.attestorBinding ~= right.attestorBinding
      or left.acceptedAttestationDigest ~= right.acceptedAttestationDigest
      or left.coordOpsRoAclDigest ~= right.coordOpsRoAclDigest
      or left.coordRedisConfigObservedDigest ~= right.coordRedisConfigObservedDigest then return false end
  local a = left.targetDeploymentEvidence
  local b = right.targetDeploymentEvidence
  if type(a) ~= 'table' or type(b) ~= 'table'
      or a.deploymentIdentity ~= b.deploymentIdentity
      or a.deploymentGeneration ~= b.deploymentGeneration
      or a.redisBuildIdentity ~= b.redisBuildIdentity
      or a.luaScriptRegistryVersion ~= b.luaScriptRegistryVersion
      or a.luaScriptRegistryDigest ~= b.luaScriptRegistryDigest
      or a.startupState ~= b.startupState
      or a.bootGeneration ~= b.bootGeneration
      or a.startupGeneration ~= b.startupGeneration
      or a.storageGeneration ~= b.storageGeneration
      or a.aofGeneration ~= b.aofGeneration
      or type(a.canonicalNodeSet) ~= 'table'
      or type(b.canonicalNodeSet) ~= 'table'
      or #a.canonicalNodeSet ~= #b.canonicalNodeSet then return false end
  for index = 1, #a.canonicalNodeSet do
    if a.canonicalNodeSet[index] ~= b.canonicalNodeSet[index] then return false end
  end
  return true
end

local function validMember(member, memberBytes, issuerId, partitionId, metadata, bindingRef, operationId, reservationId)
  if type(member) ~= 'table' or type(member.layout) ~= 'table'
      or type(member.reservation) ~= 'table' or type(member.anchor) ~= 'table'
      or type(member.memberOperation) ~= 'table' or type(member.incarnation) ~= 'table'
      or member.schemaVersion ~= 2 or member.issuerId ~= issuerId
      or member.issuerId ~= metadata.issuerId
      or member.issuerAuthGeneration ~= metadata.anchor.issuerAuthGeneration
      or member.issuerControlEpoch ~= metadata.issuerControlEpoch
      or member.issuerIndexLayoutVersion ~= metadata.layout.version
      or member.layout.partitionCount ~= metadata.layout.partitionCount
      or member.layout.partitionCapacity ~= metadata.layout.partitionCapacity
      or member.layout.mappingIdentity ~= metadata.layout.mappingIdentity
      or member.layout.descriptorBytes ~= metadata.layout.descriptorBytes
      or member.layout.descriptorDigest ~= metadata.layout.descriptorDigest
      or member.anchor.issuerCoverageOperationId ~= metadata.anchor.issuerCoverageOperationId
      or member.anchor.operationFence ~= metadata.anchor.operationFence
      or member.anchor.coverageFence ~= metadata.anchor.coverageFence
      or member.anchor.issuerInventorySnapshotRevision ~= metadata.anchor.issuerInventorySnapshotRevision
      or member.anchor.sourceCheckpointBytes ~= metadata.anchor.sourceCheckpointBytes
      or member.anchor.sourceCheckpointDigest ~= metadata.anchor.sourceCheckpointDigest
      or member.anchor.sourceEventBytes ~= metadata.anchor.sourceEventBytes
      or member.anchor.sourceEventDigest ~= metadata.anchor.sourceEventDigest
      or member.reservation.reservationId ~= reservationId
      or member.reservation.partitionId ~= partitionId
      or member.memberOperation.operationId ~= operationId
      or (bindingRef ~= nil and not canonicalBindingRef(bindingRef, member))
      or not uuid(member.accountId) or not uuid(member.tenantId)
      or not uuid(member.gameInstanceId) or not uuid(member.sessionId)
      or not uuid(member.playableStateNamespaceId) or not uuid(member.characterId)
      or not uuid(member.regionId) or not uuid(member.reservation.transitionId)
      or not uuid(member.reservation.reservationFence)
      or not uuid(member.reservation.issuerCoverageOperationId)
      or not positive(member.bindingGeneration) or not positive(member.issuerAuthGeneration)
      or not positive(member.issuerIndexLayoutVersion) or not positive(member.regionEpoch)
      or not positive(member.issuerControlEpoch)
      or not positive(member.layout.partitionCount) or not positive(member.layout.partitionCapacity)
      or not positive(member.reservation.issuerCoverageOperationFence)
      or not positive(member.reservation.coverageFence)
      or not positive(member.reservation.inventorySnapshotRevision)
      or not positive(member.reservation.inventoryRevision)
      or not positive(member.anchor.operationFence)
      or not positive(member.anchor.coverageFence)
      or not positive(member.anchor.issuerInventorySnapshotRevision)
      or not positive(member.memberOperation.operationFence)
      or not positive(member.memberOperation.coverageGeneration)
      or not digest(member.layout.descriptorDigest)
      or not digest(member.anchor.sourceCheckpointDigest)
      or (member.anchor.sourceEventDigest ~= cjson.null and not digest(member.anchor.sourceEventDigest))
      or not sameIncarnation(member.incarnation, metadata.incarnation) then return false end
  if member.reservation.lifecycle ~= 'RESERVED' and member.reservation.lifecycle ~= 'BOUND'
      and member.reservation.lifecycle ~= 'RELEASE_PENDING' and member.reservation.lifecycle ~= 'RELEASED' then
    return false
  end
  return true
end

if #KEYS ~= 1 or #ARGV < 13 then return -2 end
if type(KEYS[1]) ~= 'string' or #KEYS[1] > 512 or string.sub(KEYS[1], 1, #keyPrefix) ~= keyPrefix then
  return -2
end

local issuerId = ARGV[1]
local partitionId = ARGV[2]
local partitionCount = ARGV[3]
local partitionCapacity = ARGV[4]
local baselineCountText = ARGV[5]
if issuerId ~= 'firemud-account-service' or not nonnegative(partitionId)
    or not positive(partitionCount) or not positive(partitionCapacity)
    or not positive(baselineCountText) then return -2 end
local baselineCount = tonumber(baselineCountText)
if not baselineCount or baselineCount > maxBaselineFields then return -2 end

local cursor = 6
local baseline = {}
local baselineCapacity = 0
local baselineReservations = 0
local baselineBindings = 0
local baselineCapacityMembers = {}
local baselineBindingMembers = {}
local metadataBytes = nil
for index = 1, baselineCount do
  local field = ARGV[cursor]
  local valueBytes = ARGV[cursor + 1]
  cursor = cursor + 2
  if type(field) ~= 'string' or #field == 0 or baseline[field] ~= nil
      or type(valueBytes) ~= 'string' or #valueBytes == 0 or #valueBytes > maxMemberValueBytes then return -2 end
  baseline[field] = valueBytes
  if field == metadataField then
    metadataBytes = valueBytes
  else
    local decodedOk, value = pcall(cjson.decode, valueBytes)
    if not decodedOk or type(value) ~= 'table' or type(value.reservation) ~= 'table' then return -2 end
    if string.sub(field, 1, #capacityPrefix) == capacityPrefix then
      local reservationId = string.sub(field, #capacityPrefix + 1)
      if not uuid(reservationId) or value.reservation.reservationId ~= reservationId then return -2 end
      baselineCapacityMembers[reservationId] = valueBytes
      baselineReservations = baselineReservations + 1
      if value.reservation.lifecycle ~= 'RELEASED' then baselineCapacity = baselineCapacity + 1 end
    else
      if string.sub(field, 1, 1) == '!'
          or not canonicalBindingRef(field, value)
          or not uuid(value.reservation.reservationId) then return -2 end
      if baselineBindingMembers[value.reservation.reservationId] ~= nil then return -2 end
      baselineBindingMembers[value.reservation.reservationId] = valueBytes
      baselineBindings = baselineBindings + 1
    end
  end
end
for reservationId, memberBytes in pairs(baselineBindingMembers) do
  if baselineCapacityMembers[reservationId] ~= memberBytes then return -2 end
end
if metadataBytes == nil or #metadataBytes > maxMetadataBytes then return -2 end
local metadataOk, metadata = pcall(cjson.decode, metadataBytes)
if not metadataOk or type(metadata) ~= 'table' or metadata.schemaVersion ~= 1
    or metadata.issuerId ~= issuerId or metadata.partitionId ~= partitionId
    or type(metadata.layoutTag) ~= 'string' or #metadata.layoutTag ~= 70
    or not string.match(metadata.layoutTag, '^il%-v1%-%x+$')
    or type(metadata.bootstrap) ~= 'table' or type(metadata.build) ~= 'table'
    or type(metadata.layout) ~= 'table' or type(metadata.anchor) ~= 'table'
    or type(metadata.incarnation) ~= 'table'
    or metadata.layout.partitionCount ~= partitionCount
    or metadata.layout.partitionCapacity ~= partitionCapacity
    or metadata.layout.mappingIdentity ~= 'bindingRef/v1;sha256-u256-mod/v1'
    or metadata.anchor.issuerCoverageOperationId ~= metadata.bootstrap.operationId
    or metadata.anchor.operationFence ~= metadata.bootstrap.operationFence
    or metadata.anchor.coverageFence ~= metadata.bootstrap.coverageFence
    or metadata.anchor.issuerInventorySnapshotRevision ~= metadata.bootstrap.issuerInventorySnapshotRevision
    or metadata.issuerControlEpoch ~= metadata.bootstrap.controlEpoch
    or not uuid(metadata.bootstrap.operationId) or not uuid(metadata.bootstrap.requestId)
    or not uuid(metadata.build.operationId)
    or not positive(metadata.bootstrap.operationFence)
    or not positive(metadata.bootstrap.coverageFence)
    or metadata.bootstrap.operationFence == metadata.bootstrap.coverageFence
    or not positive(metadata.bootstrap.issuerInventorySnapshotRevision)
    or not positive(metadata.issuerControlEpoch)
    or not digest(metadata.bootstrap.cohortDigest)
    or not digest(metadata.build.planDigest) then return -2 end
local expectedKey = keyPrefix .. '{' .. metadata.layoutTag .. '}:' .. issuerId .. ':' .. partitionId
if KEYS[1] ~= expectedKey or not greaterDecimal(partitionCount, partitionId) then return -2 end

for field, valueBytes in pairs(baseline) do
  if field ~= metadataField then
    local decodedOk, member = pcall(cjson.decode, valueBytes)
    if not decodedOk or type(member) ~= 'table' or type(member.reservation) ~= 'table'
        or type(member.memberOperation) ~= 'table' then return -2 end
    if not validMember(
          member,
          valueBytes,
          issuerId,
          partitionId,
          metadata,
          nil,
          member.memberOperation.operationId,
          member.reservation.reservationId) then return -2 end
    if string.sub(field, 1, #capacityPrefix) ~= capacityPrefix
        and not canonicalBindingRef(field, member) then return -2 end
  end
end

local allowed = {}
local allowedCount = 0
local totalExpectedBytes = 0
local function putAllowed(field, valueBytes)
  if type(field) ~= 'string' or #field == 0 or allowed[field] ~= nil then return false end
  allowed[field] = valueBytes
  allowedCount = allowedCount + 1
  totalExpectedBytes = totalExpectedBytes + #field + #valueBytes
  return allowedCount <= maxHashFields and totalExpectedBytes <= maxHashBytes
end
for field, valueBytes in pairs(baseline) do
  if not putAllowed(field, valueBytes) then return -2 end
end

local operationCountText = ARGV[cursor]
cursor = cursor + 1
if not positive(operationCountText) then return -2 end
local operationCount = tonumber(operationCountText)
if not operationCount or operationCount > maxMemberOperations then return -2 end
local seenOperationIds = {}
local seenReservationIds = {}
local seenBindings = {}
local registeredOperations = {}
local localCapacity = 0
local localBindingCount = 0
local targetOperationId = ARGV[cursor + operationCount * 8]
local targetReservationId = ARGV[cursor + operationCount * 8 + 1]
local targetBindingRef = ARGV[cursor + operationCount * 8 + 2]
local targetMemberBytes = ARGV[cursor + operationCount * 8 + 3]
local targetBindingPresent = ARGV[cursor + operationCount * 8 + 4]
local plannedPartitionCapacity = ARGV[cursor + operationCount * 8 + 5]
local plannedAggregateCapacity = ARGV[cursor + operationCount * 8 + 6]
local aggregateBound = ARGV[cursor + operationCount * 8 + 7]
local expectedRevision = ARGV[cursor + operationCount * 8 + 8]
local successorRevision = ARGV[cursor + operationCount * 8 + 9]
local mutationKind = ARGV[cursor + operationCount * 8 + 10]
if not uuid(targetOperationId) or not uuid(targetReservationId)
    or type(targetBindingRef) ~= 'string' or type(targetMemberBytes) ~= 'string'
    or (targetBindingPresent ~= '0' and targetBindingPresent ~= '1')
    or not nonnegative(plannedPartitionCapacity)
    or not nonnegative(plannedAggregateCapacity)
    or not positive(aggregateBound)
    or not uuid(expectedRevision) or not uuid(successorRevision)
    or expectedRevision == successorRevision
    or (mutationKind ~= 'ADD_CAPACITY' and mutationKind ~= 'ADD_BINDING')
    or (mutationKind == 'ADD_CAPACITY' and targetBindingPresent ~= '0')
    or (mutationKind == 'ADD_BINDING' and targetBindingPresent ~= '1')
    or #ARGV ~= cursor + operationCount * 8 + 10 then return -2 end

local targetFound = false
local position = cursor
for index = 1, operationCount do
  local operationId = ARGV[position]
  local bindingRef = ARGV[position + 1]
  local reservationId = ARGV[position + 2]
  local memberBytes = ARGV[position + 3]
  local bindingPresent = ARGV[position + 4]
  local countsCapacity = ARGV[position + 5]
  local capacityMustBePresent = ARGV[position + 6]
  local bindingMustBePresent = ARGV[position + 7]
  position = position + 8
  if not uuid(operationId) or not uuid(reservationId)
      or (bindingPresent ~= '0' and bindingPresent ~= '1')
      or (countsCapacity ~= '0' and countsCapacity ~= '1')
      or (capacityMustBePresent ~= '0' and capacityMustBePresent ~= '1')
      or (bindingMustBePresent ~= '0' and bindingMustBePresent ~= '1')
      or (bindingMustBePresent == '1' and bindingPresent ~= '1')
      or type(memberBytes) ~= 'string' or #memberBytes == 0 or #memberBytes > maxMemberValueBytes
      or seenOperationIds[operationId] or seenReservationIds[reservationId]
      or seenBindings[bindingRef] then return -2 end
  local decodedOk, member = pcall(cjson.decode, memberBytes)
  if not decodedOk or not validMember(member, memberBytes, issuerId, partitionId, metadata, bindingRef, operationId, reservationId) then return -2 end
  if (countsCapacity == '1' and member.reservation.lifecycle == 'RELEASED')
      or (countsCapacity == '0'
          and (member.reservation.lifecycle ~= 'RELEASED' or bindingPresent ~= '0')) then return -2 end
  seenOperationIds[operationId] = true
  seenReservationIds[reservationId] = true
  seenBindings[bindingRef] = true
  registeredOperations[#registeredOperations + 1] = {
    operationId = operationId,
    bindingRef = bindingRef,
    reservationId = reservationId,
    memberBytes = memberBytes,
    bindingPresent = bindingPresent,
    capacityMustBePresent = capacityMustBePresent,
    bindingMustBePresent = bindingMustBePresent
  }
  if countsCapacity == '1' then localCapacity = localCapacity + 1 end
  local capacityField = capacityPrefix .. reservationId
  if not putAllowed(capacityField, memberBytes) then return -2 end
  if bindingPresent == '1' then
    if string.sub(bindingRef, 1, 1) == '!' or not putAllowed(bindingRef, memberBytes) then return -2 end
    localBindingCount = localBindingCount + 1
  end
  if operationId == targetOperationId then
    if reservationId ~= targetReservationId or bindingRef ~= targetBindingRef
        or memberBytes ~= targetMemberBytes or bindingPresent ~= targetBindingPresent then return -2 end
    targetFound = true
  end
end
if not targetFound or not seenOperationIds[targetOperationId]
    or tostring(localCapacity + baselineCapacity) ~= plannedPartitionCapacity
    or greaterDecimal(plannedPartitionCapacity, partitionCapacity)
    or greaterDecimal(plannedAggregateCapacity, aggregateBound) then return -2 end
local targetCapacityField = capacityPrefix .. targetReservationId
if not allowed[targetCapacityField] or allowed[targetCapacityField] ~= targetMemberBytes then return -2 end
if targetBindingPresent == '1' and allowed[targetBindingRef] ~= targetMemberBytes then return -2 end

-- A missing header, legacy key, wrong type, expiration, omitted baseline field, extra field,
-- or conflicting registered post-baseline value is refused before any write.
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'hash' or redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end
local actual = redis.call('HGETALL', KEYS[1])
if #actual % 2 ~= 0 or #actual / 2 > maxPhysicalFields then return -1 end
local actualCapacityCount = 0
local actualReservationCount = 0
local actualBindingCount = 0
local totalActualBytes = 0
local actualFields = {}
for index = 1, #actual, 2 do
  local field = actual[index]
  local valueBytes = actual[index + 1]
  actualFields[field] = valueBytes
  totalActualBytes = totalActualBytes + #field + #valueBytes
  if totalActualBytes > maxHashBytes then return -1 end
  local expected = allowed[field]
  if field == mutationRevisionField then
    if valueBytes ~= expectedRevision and valueBytes ~= successorRevision then return -1 end
  elseif expected == nil or expected ~= valueBytes then
    return -1
  end
  if field == mutationRevisionField then
    -- The separately validated fence is not a reservation or binding field.
  elseif string.sub(field, 1, #capacityPrefix) == capacityPrefix then
    local decodedOk, member = pcall(cjson.decode, valueBytes)
    if not decodedOk or type(member) ~= 'table' or type(member.reservation) ~= 'table' then return -1 end
    actualReservationCount = actualReservationCount + 1
    if member.reservation.lifecycle ~= 'RELEASED' then actualCapacityCount = actualCapacityCount + 1 end
  elseif field ~= metadataField and field ~= mutationRevisionField then
    actualBindingCount = actualBindingCount + 1
  end
end
for _, operation in ipairs(registeredOperations) do
  local capacity = actualFields[capacityPrefix .. operation.reservationId]
  local binding = actualFields[operation.bindingRef]
  if binding ~= nil and capacity == nil then return -1 end
  if capacity ~= nil and capacity ~= operation.memberBytes then return -1 end
  if binding ~= nil and binding ~= operation.memberBytes then return -1 end
end
for field, valueBytes in pairs(baseline) do
  if redis.call('HGET', KEYS[1], field) ~= valueBytes then return -1 end
end
if actualCapacityCount > tonumber(plannedPartitionCapacity)
    or actualReservationCount < baselineReservations
    or actualBindingCount < baselineBindings
    or actualBindingCount > baselineBindings + localBindingCount then return -1 end

local capacityBefore = redis.call('HGET', KEYS[1], targetCapacityField)
local bindingBefore = redis.call('HGET', KEYS[1], targetBindingRef)
local revisionBefore = actualFields[mutationRevisionField]
for _, operation in ipairs(registeredOperations) do
  if operation.operationId ~= targetOperationId or revisionBefore ~= successorRevision then
    local capacity = actualFields[capacityPrefix .. operation.reservationId]
    local binding = actualFields[operation.bindingRef]
    if operation.capacityMustBePresent == '1' and capacity ~= operation.memberBytes then return -1 end
    if operation.bindingMustBePresent == '1' and binding ~= operation.memberBytes then return -1 end
  end
end
if revisionBefore == successorRevision then
  if capacityBefore ~= targetMemberBytes
      or (targetBindingPresent == '1' and bindingBefore ~= targetMemberBytes)
      or (targetBindingPresent == '0' and bindingBefore ~= nil and bindingBefore ~= false) then return -1 end
  return 3
end
if revisionBefore ~= expectedRevision then return -1 end
if mutationKind == 'ADD_CAPACITY' then
  if capacityBefore ~= nil and capacityBefore ~= false then return -1 end
  if bindingBefore ~= nil and bindingBefore ~= false then return -1 end
  redis.call('HSET', KEYS[1], mutationRevisionField, successorRevision,
      targetCapacityField, targetMemberBytes)
  return 1
end
if capacityBefore ~= targetMemberBytes or (bindingBefore ~= nil and bindingBefore ~= false) then return -1 end
redis.call('HSET', KEYS[1], mutationRevisionField, successorRevision,
    targetBindingRef, targetMemberBytes)
return 2
