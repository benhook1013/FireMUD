-- Retire only one SQL-released terminal capacity field under the partition revision fence.
-- KEYS[1] is the sole canonical issuer partition hash.
local keyPrefix = 'session:game:index:issuer:'
local metadataField = '!issuer-partition-bootstrap/v1'
local revisionField = '!issuer-partition-mutation-revision/v1'
local capacityPrefix = '!issuer-reservation-capacity/v1:'
local maxMemberValueBytes = 65536
local maxMetadataBytes = 65536
local maxBaselineFields = 513
local maxLocalOperations = 512
local maxCurrentFields = 1026
local maxExpectedFields = 1025
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

if #KEYS ~= 1 or #ARGV < 14 then return -2 end
if type(KEYS[1]) ~= 'string' or #KEYS[1] > 512
    or string.sub(KEYS[1], 1, #keyPrefix) ~= keyPrefix then return -2 end
local issuerId = ARGV[1]
local partitionId = ARGV[2]
local partitionCount = ARGV[3]
local partitionCapacity = ARGV[4]
local baselineCount = tonumber(ARGV[5])
if issuerId ~= 'firemud-account-service' or not nonnegative(partitionId)
    or not positive(partitionCount) or not positive(partitionCapacity)
    or not baselineCount or baselineCount ~= baselineCount or baselineCount < 1
    or baselineCount > maxBaselineFields then return -2 end

local cursor = 6
local baseline = {}
local baselineReservations = 0
local baselineBindings = 0
local baselineCapacity = 0
local metadataBytes = nil
for index = 1, baselineCount do
  local field = ARGV[cursor]
  local value = ARGV[cursor + 1]
  cursor = cursor + 2
  if type(field) ~= 'string' or #field == 0 or baseline[field] ~= nil
      or type(value) ~= 'string' or #value == 0 or #value > maxMemberValueBytes then return -2 end
  baseline[field] = value
  if field == metadataField then
    metadataBytes = value
  elseif string.sub(field, 1, #capacityPrefix) == capacityPrefix then
    local id = string.sub(field, #capacityPrefix + 1)
    local ok, member = pcall(cjson.decode, value)
    if not uuid(id) or not ok or type(member) ~= 'table'
        or type(member.reservation) ~= 'table' or member.reservation.reservationId ~= id
        or member.reservation.partitionId ~= partitionId then return -2 end
    baselineReservations = baselineReservations + 1
    if member.reservation.lifecycle ~= 'RELEASED' then baselineCapacity = baselineCapacity + 1 end
  else
    local ok, member = pcall(cjson.decode, value)
    if not ok or type(member) ~= 'table' or type(member.reservation) ~= 'table'
        or member.reservation.bindingRef ~= field then return -2 end
    baselineBindings = baselineBindings + 1
  end
end
if metadataBytes == nil or #metadataBytes > maxMetadataBytes then return -2 end
local metadataOk, metadata = pcall(cjson.decode, metadataBytes)
if not metadataOk or type(metadata) ~= 'table' or metadata.schemaVersion ~= 1
    or metadata.issuerId ~= issuerId or metadata.partitionId ~= partitionId
    or type(metadata.layoutTag) ~= 'string' or #metadata.layoutTag ~= 70
    or not string.match(metadata.layoutTag, '^il%-v1%-%x+$')
    or type(metadata.layout) ~= 'table' or type(metadata.anchor) ~= 'table'
    or type(metadata.incarnation) ~= 'table'
    or metadata.layout.partitionCount ~= partitionCount
    or metadata.layout.partitionCapacity ~= partitionCapacity
    or metadata.layout.mappingIdentity ~= 'bindingRef/v1;sha256-u256-mod/v1'
    or not uuid(metadata.anchor.issuerCoverageOperationId)
    or not positive(metadata.anchor.operationFence)
    or not positive(metadata.anchor.coverageFence)
    or not positive(metadata.anchor.issuerInventorySnapshotRevision) then return -2 end
local expectedKey = keyPrefix .. '{' .. metadata.layoutTag .. '}:' .. issuerId .. ':' .. partitionId
if KEYS[1] ~= expectedKey or not greaterDecimal(partitionCount, partitionId) then return -2 end

local allowed = {}
local allowedCount = 0
local expectedBytes = 0
local function put(field, value)
  if type(field) ~= 'string' or #field == 0 or allowed[field] ~= nil then return false end
  allowed[field] = value
  allowedCount = allowedCount + 1
  expectedBytes = expectedBytes + #field + #value
  return allowedCount <= maxExpectedFields and expectedBytes <= maxHashBytes
end
for field, value in pairs(baseline) do if not put(field, value) then return -2 end end

local operationCount = tonumber(ARGV[cursor])
cursor = cursor + 1
if not operationCount or operationCount < 1 or operationCount > maxLocalOperations then return -2 end
local operations = {}
local operationIds = {}
local reservationIds = {}
local bindingRefs = {}
local registeredCapacity = 0
local registeredBindings = 0
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
      or type(bindingRef) ~= 'string' or #bindingRef == 0 or #bindingRef > 6144
      or type(memberBytes) ~= 'string' or #memberBytes == 0 or #memberBytes > maxMemberValueBytes
      or (bindingPresent ~= '0' and bindingPresent ~= '1')
      or (countsCapacity ~= '0' and countsCapacity ~= '1')
      or (capacityMustBePresent ~= '0' and capacityMustBePresent ~= '1')
      or (bindingMustBePresent ~= '0' and bindingMustBePresent ~= '1')
      or (bindingMustBePresent == '1' and bindingPresent ~= '1')
      or operationIds[operationId] or reservationIds[reservationId] or bindingRefs[bindingRef] then return -2 end
  local ok, member = pcall(cjson.decode, memberBytes)
  if not ok or type(member) ~= 'table' or member.schemaVersion ~= 2
      or member.issuerId ~= issuerId or type(member.reservation) ~= 'table'
      or type(member.memberOperation) ~= 'table'
      or member.reservation.bindingRef ~= bindingRef
      or member.reservation.reservationId ~= reservationId
      or member.reservation.partitionId ~= partitionId
      or member.memberOperation.operationId ~= operationId
      or not uuid(member.memberOperation.operationId)
      or (countsCapacity == '1' and member.reservation.lifecycle == 'RELEASED')
      or (countsCapacity == '0'
          and (member.reservation.lifecycle ~= 'RELEASED' or bindingPresent ~= '0')) then return -2 end
  operationIds[operationId] = true
  reservationIds[reservationId] = true
  bindingRefs[bindingRef] = true
  operations[#operations + 1] = {
    operationId=operationId, bindingRef=bindingRef, reservationId=reservationId,
    memberBytes=memberBytes, bindingPresent=bindingPresent, countsCapacity=countsCapacity,
    capacityMustBePresent=capacityMustBePresent, bindingMustBePresent=bindingMustBePresent,
    member=member
  }
  if countsCapacity == '1' then registeredCapacity = registeredCapacity + 1 end
  if bindingPresent == '1' then registeredBindings = registeredBindings + 1 end
  if not put(capacityPrefix .. reservationId, memberBytes) then return -2 end
  if bindingPresent == '1' and not put(bindingRef, memberBytes) then return -2 end
end

local releaseOperationId = ARGV[cursor]
local retirementOperationId = ARGV[cursor + 1]
local targetReservationId = ARGV[cursor + 2]
local targetBindingRef = ARGV[cursor + 3]
local releasedBytes = ARGV[cursor + 4]
local sourceRevision = ARGV[cursor + 5]
local completionRevision = ARGV[cursor + 6]
local plannedPartitionCapacity = ARGV[cursor + 7]
local plannedAggregateCapacity = ARGV[cursor + 8]
local aggregateBound = ARGV[cursor + 9]
local expectedRevision = ARGV[cursor + 10]
local successorRevision = ARGV[cursor + 11]
if #ARGV ~= cursor + 11 or not uuid(releaseOperationId) or not uuid(retirementOperationId)
    or not uuid(targetReservationId) or type(targetBindingRef) ~= 'string'
    or type(releasedBytes) ~= 'string' or #releasedBytes == 0 or #releasedBytes > maxMemberValueBytes
    or not positive(sourceRevision) or not positive(completionRevision)
    or not greaterDecimal(completionRevision, sourceRevision)
    or not nonnegative(plannedPartitionCapacity) or not nonnegative(plannedAggregateCapacity)
    or not positive(aggregateBound) or not uuid(expectedRevision) or not uuid(successorRevision)
    or expectedRevision == successorRevision
    or greaterDecimal(plannedPartitionCapacity, partitionCapacity)
    or greaterDecimal(plannedAggregateCapacity, aggregateBound) then return -2 end
local releasedOk, released = pcall(cjson.decode, releasedBytes)
if not releasedOk or type(released) ~= 'table' or released.schemaVersion ~= 2
    or type(released.reservation) ~= 'table' or type(released.memberOperation) ~= 'table'
    or released.issuerId ~= issuerId
    or released.reservation.lifecycle ~= 'RELEASED'
    or released.reservation.reservationId ~= targetReservationId
    or released.reservation.partitionId ~= partitionId
    or released.reservation.inventoryRevision ~= sourceRevision
    or released.memberOperation.operationId ~= releaseOperationId
    or released.reservation.bindingRef ~= targetBindingRef then return -2 end
local targetFound = false
for _, operation in ipairs(operations) do
  if operation.reservationId == targetReservationId then
    if operation.operationId ~= releaseOperationId
        or operation.bindingRef ~= targetBindingRef
        or operation.memberBytes ~= releasedBytes
        or operation.countsCapacity ~= '0' or operation.bindingPresent ~= '0' then return -2 end
    targetFound = true
  elseif operation.operationId == releaseOperationId then
    return -2
  end
end
if not targetFound then return -2 end
local targetCapacityField = capacityPrefix .. targetReservationId
if allowed[targetCapacityField] ~= releasedBytes then return -2 end
if tostring(baselineCapacity + registeredCapacity) ~= plannedPartitionCapacity then return -2 end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'hash' or redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end
local actual = redis.call('HGETALL', KEYS[1])
if #actual % 2 ~= 0 or #actual / 2 > maxCurrentFields then return -1 end
local actualFields = {}
local currentBytes = 0
local actualCapacity = 0
local actualReservations = 0
local actualBindings = 0
for index = 1, #actual, 2 do
  local field = actual[index]
  local value = actual[index + 1]
  actualFields[field] = value
  currentBytes = currentBytes + #field + #value
  if currentBytes > maxHashBytes then return -1 end
  if field == revisionField then
    if value ~= expectedRevision and value ~= successorRevision then return -1 end
  elseif allowed[field] == nil or allowed[field] ~= value then
    return -1
  end
  if field == revisionField or field == metadataField then
    -- Metadata and revision do not count as reservation or binding members.
  elseif string.sub(field, 1, #capacityPrefix) == capacityPrefix then
    local ok, member = pcall(cjson.decode, value)
    if not ok or type(member) ~= 'table' or type(member.reservation) ~= 'table' then return -1 end
    actualReservations = actualReservations + 1
    if member.reservation.lifecycle ~= 'RELEASED' then actualCapacity = actualCapacity + 1 end
  else
    actualBindings = actualBindings + 1
  end
end
local revisionBefore = actualFields[revisionField]
local targetCapacityBefore = actualFields[targetCapacityField]
for _, operation in ipairs(operations) do
  if operation.operationId ~= releaseOperationId or revisionBefore ~= successorRevision then
    local capacity = actualFields[capacityPrefix .. operation.reservationId]
    local binding = actualFields[operation.bindingRef]
    if operation.capacityMustBePresent == '1' and capacity ~= operation.memberBytes then return -1 end
    if operation.bindingMustBePresent == '1' and binding ~= operation.memberBytes then return -1 end
  end
end
for field, value in pairs(baseline) do
  if actualFields[field] ~= value then return -1 end
end
if actualCapacity > tonumber(plannedPartitionCapacity)
    or actualReservations < baselineReservations
    or actualReservations > baselineReservations + operationCount
    or actualBindings < baselineBindings
    or actualBindings > baselineBindings + registeredBindings then return -1 end
if revisionBefore == successorRevision then
  if targetCapacityBefore ~= nil or actualFields[targetBindingRef] == releasedBytes then return -1 end
  return 2
end
if revisionBefore ~= expectedRevision
    or targetCapacityBefore ~= releasedBytes
    or actualFields[targetBindingRef] == releasedBytes then return -1 end
redis.call('HDEL', KEYS[1], targetCapacityField)
redis.call('HSET', KEYS[1], revisionField, successorRevision)
return 1
