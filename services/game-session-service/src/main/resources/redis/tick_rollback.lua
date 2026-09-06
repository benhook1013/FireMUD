local pending = KEYS[1]
local queue = KEYS[2]
local lease = KEYS[3]
if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end

local terminalizedCount = tonumber(ARGV[2]) or -1
if terminalizedCount < 0
    or terminalizedCount ~= math.floor(terminalizedCount)
    or #ARGV ~= terminalizedCount + 2 then
  return -3
end
local terminalized = {}
for index = 1, terminalizedCount do
  terminalized[ARGV[index + 2]] = true
end

local function unwrapSerializedString(raw)
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

while true do
  local cmd = redis.call('RPOP', pending)
  if not cmd then
    break
  end
  local unwrapped = unwrapSerializedString(cmd)
  local idEnd = unwrapped and string.find(unwrapped, '|', 3, true)
  local commandId = idEnd and string.sub(unwrapped, 3, idEnd - 1)
  if not commandId or not terminalized[commandId] then
    redis.call('LPUSH', queue, cmd)
  end
end
return 1
