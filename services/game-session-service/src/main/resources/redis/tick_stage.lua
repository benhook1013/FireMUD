local queue = KEYS[1]
local pending = KEYS[2]
local lease = KEYS[3]
if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end

-- The production queue uses RedisTemplate<String, Object>, whose default
-- value serializer is JDK serialization. Keep the queue's existing Java
-- representation intact for the consumers that deserialize it, but unwrap
-- serialized Strings while validating their bytes here. Raw strings remain
-- supported for the script's direct callers and existing queue data.
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

local max = tonumber(ARGV[2])
local expectedMode = ARGV[3]
if (expectedMode ~= 'N' and expectedMode ~= 'S')
    or not max
    or max ~= math.floor(max)
    or max < 1 then
  return -3
end

-- Validate the complete candidate prefix before mutating either list. A mode
-- boundary stops FIFO staging; malformed evidence aborts atomically.
local candidateCount = 0
while candidateCount < max do
  local rawCmd = redis.call('LINDEX', queue, candidateCount)
  if not rawCmd then
    break
  end
  local cmd = unwrapSerializedString(rawCmd)
  if not cmd then
    return -2
  end
  local mode = string.sub(cmd, 1, 1)
  local delimiter = string.sub(cmd, 2, 2)
  local idEnd = string.find(cmd, '|', 3, true)
  if (mode ~= 'N' and mode ~= 'S') or delimiter ~= '|' or not idEnd then
    return -2
  end
  local commandId = string.sub(cmd, 3, idEnd - 1)
  local commandText = string.sub(cmd, idEnd + 1)
  local commandIdBlank = string.match(commandId, '%S') == nil
  local commandTextBlank = string.match(commandText, '%S') == nil
  if commandIdBlank or commandId == '-' or commandTextBlank then
    return -2
  end
  if mode ~= expectedMode then
    break
  end
  candidateCount = candidateCount + 1
end

-- Take a second snapshot before mutating either list. Redis runs this script
-- atomically, so a short queue cannot be observed after this point. Move the
-- snapshot and trim the source together; there is no post-mutation failure
-- path that could report malformed input after partially moving entries.
if candidateCount == 0 then
  return 0
end
local candidateEntries = redis.call('LRANGE', queue, 0, candidateCount - 1)
if #candidateEntries ~= candidateCount then
  return -2
end
for index = 1, candidateCount do
  redis.call('RPUSH', pending, candidateEntries[index])
end
if candidateCount > 0 then
  redis.call('LTRIM', queue, candidateCount, -1)
end
return candidateCount
