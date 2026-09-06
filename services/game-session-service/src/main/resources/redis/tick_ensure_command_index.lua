local queue = KEYS[1]
local pending = KEYS[2]
local commandIndex = KEYS[3]
local indexMarker = KEYS[4]
local lease = KEYS[5]
local INDEX_VERSION = '1'

if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end

local markerReady = redis.call('GET', indexMarker) == INDEX_VERSION
if markerReady
    and (redis.call('EXISTS', commandIndex) == 1
        or (redis.call('LLEN', queue) == 0 and redis.call('LLEN', pending) == 0)) then
  return 1
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
for _, list in ipairs({queue, pending}) do
  local values = redis.call('LRANGE', list, 0, -1)
  for index = 1, #values do
    local value, commandId = parsePayload(values[index])
    if not value then
      return -3
    end
    if valuesById[commandId] then
      return -2
    end
    valuesById[commandId] = value
    rawById[commandId] = rawById[commandId] or values[index]
  end
end

redis.call('DEL', commandIndex)
for commandId, raw in pairs(rawById) do
  redis.call('HSET', commandIndex, commandId, raw)
end
redis.call('SET', indexMarker, INDEX_VERSION)
return 1
