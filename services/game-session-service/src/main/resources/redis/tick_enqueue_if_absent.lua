local queue = KEYS[1]
local pending = KEYS[2]
local lease = KEYS[3]

-- The queue uses RedisTemplate<String, Object> with its default JDK value serializer. The
-- materializer receives the candidate using that same serializer, while the lease token is a raw
-- String argument. Existing raw queue values remain supported for recovery of older entries.
local function unwrapSerializedString(raw)
  if string.sub(raw, 1, 4) ~= string.char(172, 237, 0, 5) then
    return raw
  end

  local typeCode = string.byte(raw, 5)
  local payloadStart
  local payloadLength = 0
  if typeCode == 116 then -- TC_STRING
    local high = string.byte(raw, 6)
    local low = string.byte(raw, 7)
    if not high or not low then
      return nil
    end
    payloadLength = high * 256 + low
    payloadStart = 8
  elseif typeCode == 124 then -- TC_LONGSTRING
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

if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end

local candidate = unwrapSerializedString(ARGV[2])
if not candidate then
  return -3
end
local candidateMode = string.sub(candidate, 1, 1)
local candidateDelimiter = string.sub(candidate, 2, 2)
local candidateIdEnd = string.find(candidate, '|', 3, true)
if (candidateMode ~= 'N' and candidateMode ~= 'S')
    or candidateDelimiter ~= '|'
    or not candidateIdEnd then
  return -3
end
local candidateId = string.sub(candidate, 3, candidateIdEnd - 1)
if candidateId == '' or candidateId == '-' or string.match(candidateId, '%S') == nil then
  return -3
end
if string.match(string.sub(candidate, candidateIdEnd + 1), '%S') == nil then
  return -3
end

-- A command identity is unique across both live projections. An exact existing payload is a
-- successful materialization acknowledgement; a different payload is a fail-closed conflict.
local function findExisting(list)
  local values = redis.call('LRANGE', list, 0, -1)
  local foundExact = false
  for index = 1, #values do
    local value = unwrapSerializedString(values[index])
    if not value then
      return -3
    end
    local valueMode = string.sub(value, 1, 1)
    local valueIdDelimiter = string.sub(value, 2, 2)
    local idEnd = string.find(value, '|', 3, true)
    if (valueMode ~= 'N' and valueMode ~= 'S') or valueIdDelimiter ~= '|' or not idEnd then
      return -3
    end
    if string.sub(value, 3, idEnd - 1) == candidateId then
      if value == candidate then
        foundExact = true
      else
        return -2
      end
    end
  end
  return foundExact and 0 or nil
end

local queueResult = findExisting(queue)
if queueResult == -2 or queueResult == -3 then
  return queueResult
end
local pendingResult = findExisting(pending)
if pendingResult == -2 or pendingResult == -3 then
  return pendingResult
end
if queueResult == 0 or pendingResult == 0 then
  return 0
end

redis.call('RPUSH', queue, ARGV[2])
return 1
