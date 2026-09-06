local pending = KEYS[1]
local queue = KEYS[2]
local lease = KEYS[3]
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
return 1
