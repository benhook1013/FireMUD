-- Build/retain one exact partition from complete positive SQL-issued bootstrap work.
-- KEYS[1] is the only issuer partition hash. ARGV[7..] repeats
-- bindingRef bytes, full schema-v2 value, reservation capacity field, expected binding presence.
local keyPrefix = 'session:game:index:issuer:'
local metadataField = '!issuer-partition-bootstrap/v1'
local capacityPrefix = '!issuer-reservation-capacity/v1:'
local mutationRevisionField = '!issuer-partition-mutation-revision/v1'
local maxDecimalBytes = 256
local maxMemberOperations = 256
local maxBindingRefBytes = 6144
local maxMemberValueBytes = 65536
local maxMetadataBytes = 65536
local maxHashFields = 514
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

local function metadataMatchesMember(metadata, member, partitionId)
  local layout = metadata.layout
  local anchor = metadata.anchor
  return type(layout) == 'table' and type(anchor) == 'table'
      and member.schemaVersion == 2
      and member.issuerId == metadata.issuerId
      and member.issuerId == ARGV[1]
      and member.issuerAuthGeneration == anchor.issuerAuthGeneration
      and member.issuerControlEpoch == metadata.issuerControlEpoch
      and member.issuerIndexLayoutVersion == layout.version
      and member.layout.partitionCount == layout.partitionCount
      and member.layout.partitionCapacity == layout.partitionCapacity
      and member.layout.mappingIdentity == layout.mappingIdentity
      and member.layout.descriptorBytes == layout.descriptorBytes
      and member.layout.descriptorDigest == layout.descriptorDigest
      and member.reservation.partitionId == partitionId
      and member.anchor.issuerCoverageOperationId == anchor.issuerCoverageOperationId
      and member.anchor.operationFence == anchor.operationFence
      and member.anchor.coverageFence == anchor.coverageFence
      and member.anchor.issuerInventorySnapshotRevision == anchor.issuerInventorySnapshotRevision
      and member.anchor.sourceCheckpointBytes == anchor.sourceCheckpointBytes
      and member.anchor.sourceCheckpointDigest == anchor.sourceCheckpointDigest
      and member.anchor.sourceEventBytes == anchor.sourceEventBytes
      and member.anchor.sourceEventDigest == anchor.sourceEventDigest
      and member.anchor.issuerCoverageOperationId == metadata.bootstrap.operationId
      and member.anchor.operationFence == metadata.bootstrap.operationFence
      and member.anchor.coverageFence == metadata.bootstrap.coverageFence
      and member.anchor.issuerInventorySnapshotRevision == metadata.bootstrap.issuerInventorySnapshotRevision
      and sameIncarnation(member.incarnation, metadata.incarnation)
end

if #KEYS ~= 1 or #ARGV < 7 then return -2 end
if type(KEYS[1]) ~= 'string' or #KEYS[1] > 512 or string.sub(KEYS[1], 1, #keyPrefix) ~= keyPrefix then
  return -2
end

local issuerId = ARGV[1]
local partitionId = ARGV[2]
local partitionCount = ARGV[3]
local partitionCapacity = ARGV[4]
local metadataBytes = ARGV[5]
local initialPhysicalRevision = ARGV[6]
local memberCountText = ARGV[7]
if issuerId ~= 'firemud-account-service' or not nonnegative(partitionId)
    or not positive(partitionCount) or not positive(partitionCapacity)
    or not uuid(initialPhysicalRevision) or not nonnegative(memberCountText) then return -2 end
local memberCount = tonumber(memberCountText)
if not memberCount or memberCount > maxMemberOperations
    or #ARGV ~= 7 + memberCount * 4 then return -2 end
if #metadataBytes == 0 or #metadataBytes > maxMetadataBytes then return -2 end

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
    or not positive(metadata.layout.version)
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
    or not digest(metadata.build.planDigest)
    or not nonnegative(metadata.plannedAggregateCapacityCount)
    or not nonnegative(metadata.plannedPartitionReservationCount)
    or not nonnegative(metadata.plannedPartitionCapacityCount)
    or metadata.plannedPartitionReservationCount ~= memberCountText then return -2 end

local expectedKey =
    keyPrefix .. '{' .. metadata.layoutTag .. '}:' .. issuerId .. ':' .. partitionId
if KEYS[1] ~= expectedKey then return -2 end
if not greaterDecimal(partitionCount, partitionId) then return -2 end

local expected = {}
local expectedCount = 0
local totalExpectedBytes = #metadataField + #metadataBytes
local function putExpected(field, value)
  if type(field) ~= 'string' or #field == 0 or expected[field] ~= nil then return false end
  expected[field] = value
  expectedCount = expectedCount + 1
  totalExpectedBytes = totalExpectedBytes + #field + #value
  return totalExpectedBytes <= maxHashBytes and expectedCount <= maxHashFields
end
if not putExpected(metadataField, metadataBytes) then return -2 end
if not putExpected(mutationRevisionField, initialPhysicalRevision) then return -2 end

local seenReservations = {}
local seenBindings = {}
local seenMemberOperations = {}
local actualCapacityCount = 0
local actualBindingCount = 0
local totalArgumentBytes = #metadataBytes
for index = 0, memberCount - 1 do
  local offset = 8 + index * 4
  local bindingRef = ARGV[offset]
  local memberBytes = ARGV[offset + 1]
  local capacityField = ARGV[offset + 2]
  local bindingPresent = ARGV[offset + 3]
  if bindingPresent ~= '0' and bindingPresent ~= '1' then return -2 end
  if type(memberBytes) ~= 'string' or #memberBytes == 0 or #memberBytes > maxMemberValueBytes then return -2 end
  totalArgumentBytes = totalArgumentBytes + #bindingRef + #memberBytes + #capacityField
  if totalArgumentBytes > maxHashBytes then return -2 end
  local decodedOk, member = pcall(cjson.decode, memberBytes)
  if not decodedOk or type(member) ~= 'table' or type(member.reservation) ~= 'table'
      or type(member.layout) ~= 'table' or type(member.anchor) ~= 'table'
      or type(member.incarnation) ~= 'table'
      or not metadataMatchesMember(metadata, member, partitionId)
      or not canonicalBindingRef(bindingRef, member) then return -2 end
  local lifecycle = member.reservation.lifecycle
  if lifecycle ~= 'RESERVED' and lifecycle ~= 'BOUND'
      and lifecycle ~= 'RELEASE_PENDING' and lifecycle ~= 'RELEASED' then return -2 end
  local reservationId = member.reservation.reservationId
  if not uuid(reservationId) or seenReservations[reservationId]
      or not uuid(member.reservation.transitionId)
      or not uuid(member.reservation.reservationFence)
      or not uuid(member.reservation.issuerCoverageOperationId)
      or not positive(member.reservation.issuerCoverageOperationFence)
      or not positive(member.reservation.coverageFence)
      or not positive(member.reservation.inventorySnapshotRevision)
      or not positive(member.reservation.inventoryRevision)
      or not uuid(member.accountId) or not uuid(member.tenantId)
      or not uuid(member.playableStateNamespaceId) or not uuid(member.characterId)
      or not uuid(member.gameInstanceId) or not uuid(member.regionId)
      or not positive(member.bindingGeneration) or not positive(member.issuerAuthGeneration)
      or not positive(member.issuerIndexLayoutVersion) or not positive(member.regionEpoch)
      or not positive(member.issuerControlEpoch)
      or not positive(member.runtimeGameInstanceId)
      or not digest(member.layout.descriptorDigest)
      or not digest(member.anchor.sourceCheckpointDigest)
      or (member.anchor.sourceEventDigest ~= cjson.null and not digest(member.anchor.sourceEventDigest)) then
    return -2
  end
  seenReservations[reservationId] = true
  if seenBindings[bindingRef] then return -2 end
  seenBindings[bindingRef] = true
  if member.reservation.bindingRef ~= bindingRef
      or member.reservation.reservationId == nil
      or member.reservation.partitionId ~= partitionId
      or member.reservation.issuerCoverageOperationId == nil
      or member.reservation.issuerCoverageOperationFence == nil
      or member.reservation.coverageFence == nil
      or member.reservation.inventorySnapshotRevision == nil
      or member.memberOperation == nil
      or not uuid(member.memberOperation.operationId)
      or seenMemberOperations[member.memberOperation.operationId]
      or not positive(member.memberOperation.operationFence)
      or not positive(member.memberOperation.coverageGeneration) then return -2 end
  seenMemberOperations[member.memberOperation.operationId] = true
  local expectedCapacityField = capacityPrefix .. reservationId
  if capacityField ~= expectedCapacityField
      or not putExpected(capacityField, memberBytes) then return -2 end
  if lifecycle ~= 'RELEASED' then actualCapacityCount = actualCapacityCount + 1 end
  if bindingPresent == '1' then
    if string.sub(bindingRef, 1, 1) == '!' or not putExpected(bindingRef, memberBytes) then return -2 end
    actualBindingCount = actualBindingCount + 1
  end
end

if greaterDecimal(tostring(actualCapacityCount), partitionCapacity)
    or tostring(actualCapacityCount) ~= metadata.plannedPartitionCapacityCount
    or actualBindingCount > memberCount then return -2 end

-- Validate TYPE, persistence and every current field before the first mutation.
local kind = redis.call('TYPE', KEYS[1]).ok
local ttl = redis.call('PTTL', KEYS[1])
if kind == 'none' then
  if ttl ~= -2 then return -1 end
elseif kind == 'hash' then
  if ttl ~= -1 then return -1 end
else
  return -1
end
local currentLength = redis.call('HLEN', KEYS[1])
if currentLength < 0 or currentLength > maxHashFields or currentLength > expectedCount then return -1 end
local current = redis.call('HGETALL', KEYS[1])
if #current ~= currentLength * 2 then return -1 end
local totalCurrentBytes = 0
local present = {}
for index = 1, #current, 2 do
  local field = current[index]
  local value = current[index + 1]
  totalCurrentBytes = totalCurrentBytes + #field + #value
  if totalCurrentBytes > maxHashBytes or expected[field] == nil or expected[field] ~= value then
    return -1
  end
  if present[field] then return -1 end
  present[field] = true
end

if kind == 'none' then
  if currentLength ~= 0 or next(present) ~= nil then return -1 end
  local initial = {}
  for field, value in pairs(expected) do
    initial[#initial + 1] = field
    initial[#initial + 1] = value
  end
  if #initial == 0 or #initial % 2 ~= 0 then return -1 end
  redis.call('HSET', KEYS[1], unpack(initial))
  return 1
end
if currentLength ~= expectedCount then return -1 end
return 2
