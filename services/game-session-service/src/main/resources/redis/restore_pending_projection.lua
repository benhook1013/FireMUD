local pending = KEYS[1]
local queue = KEYS[2]
local commandIndex = KEYS[3]
local lease = KEYS[4]
local argumentCount = #ARGV
if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end

local pendingCount = tonumber(ARGV[2])
if not pendingCount
    or pendingCount < 0
    or pendingCount ~= math.floor(pendingCount) then
  return 0
end

local pendingPayloadStartIndex = 3
local sealedCountIndex = pendingPayloadStartIndex + pendingCount
if sealedCountIndex > argumentCount then
  return 0
end
local sealedCount = tonumber(ARGV[sealedCountIndex])
if not sealedCount
    or sealedCount < 0
    or sealedCount ~= math.floor(sealedCount) then
  return 0
end

local sealedPayloadStartIndex = sealedCountIndex + 1
local redisOnlyCountIndex = sealedPayloadStartIndex + sealedCount
if redisOnlyCountIndex > argumentCount then
  return 0
end
local redisOnlyCount = tonumber(ARGV[redisOnlyCountIndex])
if not redisOnlyCount
    or redisOnlyCount < 0
    or redisOnlyCount ~= math.floor(redisOnlyCount) then
  return 0
end

local redisOnlyPayloadStartIndex = redisOnlyCountIndex + 1
local terminalizedCountIndex = redisOnlyPayloadStartIndex + redisOnlyCount
if terminalizedCountIndex > argumentCount then
  return 0
end
local terminalizedCount = tonumber(ARGV[terminalizedCountIndex])
if not terminalizedCount
    or terminalizedCount < 0
    or terminalizedCount ~= math.floor(terminalizedCount) then
  return 0
end

local terminalizedPayloadStartIndex = terminalizedCountIndex + 1
local expectedArgumentCount = terminalizedPayloadStartIndex + terminalizedCount - 1
if expectedArgumentCount ~= argumentCount then
  return 0
end

local function unwrapSerializedString(raw)
  if not raw then
    return nil
  end
  if string.sub(raw, 1, 4) ~= string.char(172, 237, 0, 5) then
    return raw
  end
  local typeCode = string.byte(raw, 5)
  local payloadStart
  local payloadLength = 0
  if typeCode == 116 then
    local high = string.byte(raw, 6)
    local low = string.byte(raw, 7)
    if not high or not low then
      return nil
    end
    payloadLength = high * 256 + low
    payloadStart = 8
  elseif typeCode == 124 then
    payloadStart = 14
    for index = 6, 13 do
      local byte = string.byte(raw, index)
      if not byte then
        return nil
      end
      payloadLength = payloadLength * 256 + byte
    end
  else
    return nil
  end
  if payloadLength ~= #raw - payloadStart + 1 then
    return nil
  end
  return string.sub(raw, payloadStart)
end

local function parsePayload(raw)
  local value = unwrapSerializedString(raw)
  if not value then
    return nil
  end
  local mode = string.sub(value, 1, 1)
  local delimiter = string.sub(value, 2, 2)
  local idEnd = string.find(value, '|', 3, true)
  if (mode ~= 'N' and mode ~= 'S') or delimiter ~= '|' or not idEnd then
    return nil
  end
  local commandId = string.sub(value, 3, idEnd - 1)
  if commandId == '' or commandId == '-' or string.match(commandId, '%S') == nil then
    return nil
  end
  if string.match(string.sub(value, idEnd + 1), '%S') == nil then
    return nil
  end
  return value, commandId
end

local valuesById = {}
local rawById = {}
local terminalizedIds = {}
local function collectPayloads(startIndex, count, terminalized)
  for offset = 0, count - 1 do
    local raw = ARGV[startIndex + offset]
    local value, commandId = parsePayload(raw)
    if not value then
      return -2
    end
    if valuesById[commandId] and valuesById[commandId] ~= value then
      return -3
    end
    valuesById[commandId] = value
    rawById[commandId] = rawById[commandId] or raw
    if terminalized then
      terminalizedIds[commandId] = true
    end
  end
  return 1
end

local result = collectPayloads(pendingPayloadStartIndex, pendingCount, false)
if result ~= 1 then
  return result
end
result = collectPayloads(sealedPayloadStartIndex, sealedCount, false)
if result ~= 1 then
  return result
end
result = collectPayloads(redisOnlyPayloadStartIndex, redisOnlyCount, false)
if result ~= 1 then
  return result
end
result = collectPayloads(terminalizedPayloadStartIndex, terminalizedCount, true)
if result ~= 1 then
  return result
end

for commandId, value in pairs(valuesById) do
  local indexedRaw = redis.call('HGET', commandIndex, commandId)
  if indexedRaw then
    local indexedValue, indexedId = parsePayload(indexedRaw)
    if not indexedValue or indexedId ~= commandId then
      return -2
    end
    if indexedValue ~= value then
      return -3
    end
  end
end

local pendingPayloadIndex = pendingPayloadStartIndex
for index = 1, pendingCount do
  redis.call('LREM', pending, 0, ARGV[pendingPayloadIndex])
  pendingPayloadIndex = pendingPayloadIndex + 1
end

local sealedPayloadIndex = sealedPayloadStartIndex
for index = 1, sealedCount do
  redis.call('RPUSH', pending, ARGV[sealedPayloadIndex])
  sealedPayloadIndex = sealedPayloadIndex + 1
end

for index = redisOnlyCount, 1, -1 do
  local payload = ARGV[redisOnlyPayloadStartIndex + index - 1]
  redis.call('LREM', queue, 0, payload)
  redis.call('LPUSH', queue, payload)
end
local terminalizedPayloadIndex = terminalizedPayloadStartIndex
for index = 1, terminalizedCount do
  local payload = ARGV[terminalizedPayloadIndex]
  redis.call('LREM', pending, 0, payload)
  redis.call('LREM', queue, 0, payload)
  terminalizedPayloadIndex = terminalizedPayloadIndex + 1
end

for commandId, raw in pairs(rawById) do
  redis.call('HSET', commandIndex, commandId, raw)
end
for commandId, _ in pairs(terminalizedIds) do
  redis.call('HDEL', commandIndex, commandId)
end
return 1
