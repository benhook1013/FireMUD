local pending = KEYS[1]
local queue = KEYS[2]
local commandIndex = KEYS[3]
local lease = KEYS[4]
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

local function parseCommandId(raw)
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
  return commandId
end

local processed = {}
while true do
  local cmd = redis.call('RPOP', pending)
  if not cmd then
    break
  end
  local commandId = parseCommandId(cmd)
  if not commandId then
    table.insert(processed, {raw = cmd, commandId = nil})
    for index = #processed, 1, -1 do
      redis.call('RPUSH', pending, processed[index].raw)
    end
    return -2
  end
  table.insert(processed, {raw = cmd, commandId = commandId})
end

for index = 1, #processed do
  local entry = processed[index]
  if terminalized[entry.commandId] then
    redis.call('HDEL', commandIndex, entry.commandId)
  else
    redis.call('LPUSH', queue, entry.raw)
  end
end
return 1
