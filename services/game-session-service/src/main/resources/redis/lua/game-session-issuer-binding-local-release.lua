-- Project one SQL-issued terminal successor and remove only its exact original binding.
-- KEYS[1] is the sole canonical issuer partition hash.
local keyPrefix = 'session:game:index:issuer:'
local metadataField = '!issuer-partition-bootstrap/v1'
local mutationRevisionField = '!issuer-partition-mutation-revision/v1'
local capacityPrefix = '!issuer-reservation-capacity/v1:'
local maxMemberValueBytes = 65536
local maxMetadataBytes = 65536
local maxMemberOperations = 256
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
  return type(value) == 'string' and #value > 0 and #value <= 256
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
local function countKeys(value)
  local result = 0
  for _ in pairs(value) do result = result + 1 end
  return result
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
      or countKeys(a) ~= 11 or countKeys(b) ~= 11
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
local function memberShape(member, issuerId, partitionId, metadata, bindingRef, operationId, reservationId)
  if type(member) ~= 'table' or countKeys(member) ~= 21
      or type(member.layout) ~= 'table' or countKeys(member.layout) ~= 5
      or type(member.reservation) ~= 'table' or countKeys(member.reservation) ~= 11
      or type(member.anchor) ~= 'table' or countKeys(member.anchor) ~= 8
      or type(member.memberOperation) ~= 'table' or countKeys(member.memberOperation) ~= 3
      or type(member.incarnation) ~= 'table' or countKeys(member.incarnation) ~= 5
      or type(member.incarnation.targetDeploymentEvidence) ~= 'table'
      or member.schemaVersion ~= 2 or member.issuerId ~= issuerId
      or member.issuerId ~= metadata.issuerId
      or member.issuerAuthGeneration ~= metadata.anchor.issuerAuthGeneration
      or member.issuerControlEpoch ~= metadata.issuerControlEpoch
      or member.issuerIndexLayoutVersion ~= metadata.layout.version
      or member.layout.partitionCount ~= partitionId.partitionCount
      or member.layout.partitionCapacity ~= partitionId.partitionCapacity
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
      or member.reservation.partitionId ~= partitionId.partitionId
      or member.memberOperation.operationId ~= operationId
      or (bindingRef ~= nil and member.reservation.bindingRef ~= bindingRef)
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
  return member.reservation.lifecycle == 'RESERVED'
      or member.reservation.lifecycle == 'BOUND'
      or member.reservation.lifecycle == 'RELEASE_PENDING'
      or member.reservation.lifecycle == 'RELEASED'
end
local function sameReleaseSource(old, released, originalOperationId, releaseOperationId, sourceRevision)
  if type(old) ~= 'table' or type(released) ~= 'table'
      or old.schemaVersion ~= 2 or released.schemaVersion ~= 2
      or old.reservation.lifecycle ~= 'RESERVED'
      or released.reservation.lifecycle ~= 'RELEASED'
      or released.reservation.inventoryRevision ~= sourceRevision
      or old.memberOperation.operationId ~= originalOperationId
      or released.memberOperation.operationId ~= releaseOperationId
      or not positive(old.memberOperation.operationFence)
      or not positive(old.memberOperation.coverageGeneration)
      or not positive(released.memberOperation.operationFence)
      or not positive(released.memberOperation.coverageGeneration)
      or not greaterDecimal(released.memberOperation.operationFence, old.memberOperation.operationFence)
      or not greaterDecimal(released.memberOperation.coverageGeneration, old.memberOperation.coverageGeneration)
      or not greaterDecimal(released.reservation.inventoryRevision, old.reservation.inventoryRevision) then return false end
  local sameRoot = {
    'issuerId','accountId','tenantId','gameInstanceId','sessionId','bindingGeneration',
    'issuerAuthGeneration','issuerIndexLayoutVersion','playableStateNamespaceId','characterId',
    'playableStateScope','runtimeGameInstanceId','regionId','regionEpoch','issuerControlEpoch'
  }
  for _, field in ipairs(sameRoot) do if old[field] ~= released[field] then return false end end
  local ol = old.layout; local nl = released.layout
  for _, field in ipairs({'partitionCount','partitionCapacity','mappingIdentity','descriptorBytes','descriptorDigest'}) do
    if ol[field] ~= nl[field] then return false end
  end
  local oa = old.anchor; local na = released.anchor
  for _, field in ipairs({'issuerCoverageOperationId','operationFence','coverageFence',
      'issuerInventorySnapshotRevision','sourceCheckpointBytes','sourceCheckpointDigest',
      'sourceEventBytes','sourceEventDigest'}) do
    if oa[field] ~= na[field] then return false end
  end
  local orv = old.reservation; local nrv = released.reservation
  for _, field in ipairs({'reservationId','bindingRef','transitionId','partitionId','reservationFence',
      'issuerCoverageOperationId','issuerCoverageOperationFence','coverageFence',
      'inventorySnapshotRevision'}) do
    if orv[field] ~= nrv[field] then return false end
  end
  return sameIncarnation(old.incarnation, released.incarnation)
end

if #KEYS ~= 1 or #ARGV < 16 then return -2 end
if type(KEYS[1]) ~= 'string' or #KEYS[1] > 512 or string.sub(KEYS[1], 1, #keyPrefix) ~= keyPrefix then return -2 end
local issuerId = ARGV[1]
local partitionId = ARGV[2]
local partitionCount = ARGV[3]
local partitionCapacity = ARGV[4]
local baselineCountText = ARGV[5]
if issuerId ~= 'firemud-account-service' or not nonnegative(partitionId)
    or not positive(partitionCount) or not positive(partitionCapacity)
    or not positive(baselineCountText) then return -2 end
local baselineCount = tonumber(baselineCountText)
if not baselineCount or baselineCount ~= baselineCount
    or baselineCount == math.huge or baselineCount == -math.huge
    or baselineCount < 1 or baselineCount > maxBaselineFields then return -2 end
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
  elseif string.sub(field, 1, #capacityPrefix) == capacityPrefix then
    local ok, value = pcall(cjson.decode, valueBytes)
    local reservationId = string.sub(field, #capacityPrefix + 1)
    if not ok or type(value) ~= 'table' or type(value.reservation) ~= 'table'
        or not uuid(reservationId) or value.reservation.reservationId ~= reservationId
        or not nonnegative(value.reservation.partitionId) then return -2 end
    baselineCapacityMembers[reservationId] = valueBytes
    baselineReservations = baselineReservations + 1
    if value.reservation.lifecycle ~= 'RELEASED' then baselineCapacity = baselineCapacity + 1 end
  else
    local ok, value = pcall(cjson.decode, valueBytes)
    if not ok or type(value) ~= 'table' or type(value.reservation) ~= 'table'
        or string.sub(field, 1, 1) == '!' or value.reservation.bindingRef ~= field then return -2 end
    if baselineBindingMembers[value.reservation.reservationId] ~= nil then return -2 end
    baselineBindingMembers[value.reservation.reservationId] = valueBytes
    baselineBindings = baselineBindings + 1
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
    or not positive(metadata.bootstrap.issuerInventorySnapshotRevision)
    or not positive(metadata.issuerControlEpoch)
    or not digest(metadata.bootstrap.cohortDigest)
    or not digest(metadata.build.planDigest) then return -2 end
local expectedKey = keyPrefix .. '{' .. metadata.layoutTag .. '}:' .. issuerId .. ':' .. partitionId
if KEYS[1] ~= expectedKey or not greaterDecimal(partitionCount, partitionId) then return -2 end
local partition = {partitionId=partitionId, partitionCount=partitionCount, partitionCapacity=partitionCapacity}
for field, valueBytes in pairs(baseline) do
  if field ~= metadataField then
    local ok, member = pcall(cjson.decode, valueBytes)
    if not ok or type(member) ~= 'table'
        or type(member.memberOperation) ~= 'table'
        or type(member.reservation) ~= 'table' then return -2 end
    if not memberShape(member, issuerId, partition, metadata, nil,
        member.memberOperation.operationId, member.reservation.reservationId) then return -2 end
  end
end

local allowed = {}
local allowedCount = 0
local allowedBytes = 0
local function put(field, value)
  if type(field) ~= 'string' or #field == 0 or allowed[field] ~= nil then return false end
  allowed[field] = value; allowedCount = allowedCount + 1; allowedBytes = allowedBytes + #field + #value
  return allowedCount <= maxHashFields and allowedBytes <= maxHashBytes
end
for field, value in pairs(baseline) do if not put(field, value) then return -2 end end
local operationCount = tonumber(ARGV[cursor])
cursor = cursor + 1
if not operationCount or operationCount < 1 or operationCount > maxMemberOperations then return -2 end
local operations = {}
local seenOperations = {}; local seenReservations = {}; local seenBindings = {}
local registeredCapacity = 0; local registeredBindings = 0
for index = 1, operationCount do
  local operationId = ARGV[cursor]
  local bindingRef = ARGV[cursor + 1]
  local reservationId = ARGV[cursor + 2]
  local memberBytes = ARGV[cursor + 3]
  local bindingPresent = ARGV[cursor + 4]
  local countsCapacity = ARGV[cursor + 5]
  local capacityMustBePresent = ARGV[cursor + 6]
  local bindingMustBePresent = ARGV[cursor + 7]
  cursor = cursor + 8
  if not uuid(operationId) or not uuid(reservationId)
      or type(bindingRef) ~= 'string' or #bindingRef == 0
      or type(memberBytes) ~= 'string' or #memberBytes == 0 or #memberBytes > maxMemberValueBytes
      or (bindingPresent ~= '0' and bindingPresent ~= '1')
      or (countsCapacity ~= '0' and countsCapacity ~= '1')
      or (capacityMustBePresent ~= '0' and capacityMustBePresent ~= '1')
      or (bindingMustBePresent ~= '0' and bindingMustBePresent ~= '1')
      or (bindingMustBePresent == '1' and bindingPresent ~= '1')
      or seenOperations[operationId] or seenReservations[reservationId] or seenBindings[bindingRef] then return -2 end
  local ok, member = pcall(cjson.decode, memberBytes)
  if not ok or not memberShape(member, issuerId, partition, metadata, bindingRef, operationId, reservationId) then return -2 end
  if countsCapacity == '1' and member.reservation.lifecycle == 'RELEASED' then return -2 end
  if countsCapacity == '0' and (member.reservation.lifecycle ~= 'RELEASED' or bindingPresent ~= '0') then return -2 end
  seenOperations[operationId] = true; seenReservations[reservationId] = true; seenBindings[bindingRef] = true
  operations[#operations + 1] = {operationId=operationId, bindingRef=bindingRef,
      reservationId=reservationId, memberBytes=memberBytes,
      bindingPresent=bindingPresent, countsCapacity=countsCapacity,
      capacityMustBePresent=capacityMustBePresent, bindingMustBePresent=bindingMustBePresent}
  if countsCapacity == '1' then registeredCapacity = registeredCapacity + 1 end
  if bindingPresent == '1' then registeredBindings = registeredBindings + 1 end
  if not put(capacityPrefix .. reservationId, memberBytes) then return -2 end
  if bindingPresent == '1' and not put(bindingRef, memberBytes) then return -2 end
end

local originalOperationId = ARGV[cursor]
local releaseOperationId = ARGV[cursor + 1]
local targetReservationId = ARGV[cursor + 2]
local targetBindingRef = ARGV[cursor + 3]
local originalBytes = ARGV[cursor + 4]
local releasedBytes = ARGV[cursor + 5]
local sourceRevision = ARGV[cursor + 6]
local plannedPartition = ARGV[cursor + 7]
local plannedAggregate = ARGV[cursor + 8]
local aggregateBound = ARGV[cursor + 9]
local expectedRevision = ARGV[cursor + 10]
local successorRevision = ARGV[cursor + 11]
if #ARGV ~= cursor + 11 or not uuid(originalOperationId) or not uuid(releaseOperationId)
    or originalOperationId == releaseOperationId or not uuid(targetReservationId)
    or type(targetBindingRef) ~= 'string' or type(originalBytes) ~= 'string'
    or type(releasedBytes) ~= 'string' or #originalBytes == 0 or #releasedBytes == 0
    or #originalBytes > maxMemberValueBytes or #releasedBytes > maxMemberValueBytes
    or not positive(sourceRevision) or not nonnegative(plannedPartition)
    or not nonnegative(plannedAggregate) or not positive(aggregateBound)
    or not uuid(expectedRevision) or not uuid(successorRevision)
    or expectedRevision == successorRevision
    or greaterDecimal(plannedPartition, partitionCapacity)
    or greaterDecimal(plannedAggregate, aggregateBound) then return -2 end
local oldOk, oldMember = pcall(cjson.decode, originalBytes)
local newOk, newMember = pcall(cjson.decode, releasedBytes)
if not oldOk or not newOk
    or not memberShape(oldMember, issuerId, partition, metadata, targetBindingRef, originalOperationId, targetReservationId)
    or not memberShape(newMember, issuerId, partition, metadata, targetBindingRef, releaseOperationId, targetReservationId)
    or not sameReleaseSource(oldMember, newMember, originalOperationId, releaseOperationId, sourceRevision) then return -2 end
local targetRegistered = false
local targetRegisteredReleased = false
for _, operation in ipairs(operations) do
  if operation.reservationId == targetReservationId then
    if operation.bindingRef ~= targetBindingRef then return -2 end
    if operation.operationId == originalOperationId and operation.memberBytes == originalBytes
        and operation.countsCapacity == '1' then targetRegistered = true
    elseif operation.operationId == releaseOperationId and operation.memberBytes == releasedBytes
        and operation.countsCapacity == '0' and operation.bindingPresent == '0' then
      targetRegistered = true
      targetRegisteredReleased = true
    else return -2 end
  elseif operation.operationId == originalOperationId or operation.operationId == releaseOperationId then
    return -2
  end
end
if not targetRegistered then return -2 end
local targetCapacityField = capacityPrefix .. targetReservationId
if allowed[targetCapacityField] == nil then
  if not put(targetCapacityField, releasedBytes) then return -2 end
else
  allowed[targetCapacityField] = releasedBytes
end
-- Keep the original binding in the allowed set only as a permitted preimage; the postimage removes it.
if allowed[targetBindingRef] == nil and not put(targetBindingRef, originalBytes) then return -2 end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'hash' or redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end
local actual = redis.call('HGETALL', KEYS[1])
if #actual % 2 ~= 0 or #actual / 2 > maxPhysicalFields then return -1 end
local actualFields = {}; local actualCapacity = 0; local actualReservations = 0; local actualBindings = 0
local actualBytes = 0
for index = 1, #actual, 2 do
  local field = actual[index]; local valueBytes = actual[index + 1]
  actualFields[field] = valueBytes; actualBytes = actualBytes + #field + #valueBytes
  if actualBytes > maxHashBytes then return -1 end
  local expected = allowed[field]
  if field == mutationRevisionField then
    if valueBytes ~= expectedRevision and valueBytes ~= successorRevision then return -1 end
  elseif field == targetCapacityField then
    if valueBytes ~= originalBytes and valueBytes ~= releasedBytes then return -1 end
  elseif field == targetBindingRef then
    if valueBytes ~= originalBytes then return -1 end
  elseif expected == nil or expected ~= valueBytes then return -1 end
  if field == mutationRevisionField then
    -- The separately validated fence is not a reservation or binding field.
  elseif string.sub(field, 1, #capacityPrefix) == capacityPrefix then
    local ok, member = pcall(cjson.decode, valueBytes)
    if not ok or type(member) ~= 'table' or type(member.reservation) ~= 'table' then return -1 end
    actualReservations = actualReservations + 1
    if member.reservation.lifecycle ~= 'RELEASED' then actualCapacity = actualCapacity + 1 end
  elseif field ~= metadataField then actualBindings = actualBindings + 1 end
end
for field, valueBytes in pairs(baseline) do
  if redis.call('HGET', KEYS[1], field) ~= valueBytes then return -1 end
end
local targetCapacityBefore = actualFields[targetCapacityField]
local targetBindingBefore = actualFields[targetBindingRef]
if targetBindingBefore ~= nil and targetCapacityBefore ~= originalBytes then return -1 end
if targetCapacityBefore == releasedBytes and targetBindingBefore ~= nil then return -1 end
if targetCapacityBefore ~= nil and targetCapacityBefore ~= originalBytes and targetCapacityBefore ~= releasedBytes then return -1 end
if targetRegisteredReleased
    and (targetCapacityBefore ~= releasedBytes or targetBindingBefore ~= nil) then return -1 end
if actualCapacity > tonumber(plannedPartition)
    or actualReservations < baselineReservations
    or actualReservations > baselineReservations + operationCount
    or actualBindings < baselineBindings
    or actualBindings > baselineBindings + registeredBindings then return -1 end

local revisionBefore = actualFields[mutationRevisionField]
for _, operation in ipairs(operations) do
  if operation.operationId ~= originalOperationId or revisionBefore ~= successorRevision then
    local capacity = actualFields[capacityPrefix .. operation.reservationId]
    local binding = actualFields[operation.bindingRef]
    if operation.capacityMustBePresent == '1' and capacity ~= operation.memberBytes then return -1 end
    if operation.bindingMustBePresent == '1' and binding ~= operation.memberBytes then return -1 end
  end
end
if revisionBefore == successorRevision then
  if targetCapacityBefore ~= releasedBytes or targetBindingBefore ~= nil then return -1 end
  return 2
end
if revisionBefore ~= expectedRevision
    or targetCapacityBefore ~= originalBytes
    or targetBindingBefore ~= originalBytes then return -1 end
redis.call('HSET', KEYS[1], mutationRevisionField, successorRevision,
    targetCapacityField, releasedBytes)
redis.call('HDEL', KEYS[1], targetBindingRef)
return 1
