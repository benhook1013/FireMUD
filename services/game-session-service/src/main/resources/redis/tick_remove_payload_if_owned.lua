local queue = KEYS[1]
local pending = KEYS[2]
local commandIndex = KEYS[3]
local lease = KEYS[4]

if redis.call('GET', lease) ~= ARGV[1] then
  return -1
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

local candidate, candidateId = parsePayload(ARGV[2])
if not candidate then
  return -2
end
local indexedRaw = redis.call('HGET', commandIndex, candidateId)
if indexedRaw then
  local indexedValue, indexedId = parsePayload(indexedRaw)
  if not indexedValue or indexedId ~= candidateId then
    return -2
  end
  if indexedValue ~= candidate then
    return -3
  end
end

redis.call('LREM', queue, 0, ARGV[2])
redis.call('LREM', pending, 0, ARGV[2])
redis.call('HDEL', commandIndex, candidateId)
return 1
