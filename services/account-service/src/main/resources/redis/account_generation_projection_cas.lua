if #KEYS ~= 1 or #ARGV ~= 3 then
  return -2
end

local mode = ARGV[1]
local expectedBytes = ARGV[2]
local candidateBytes = ARGV[3]

if mode ~= 'ABSENT' and mode ~= 'PRESENT' and mode ~= 'VERIFY' then
  return -2
end

if mode == 'VERIFY' then
  if expectedBytes == '' or candidateBytes ~= '' then
    return -2
  end
elseif mode == 'ABSENT' then
  if expectedBytes ~= '' or candidateBytes == '' then
    return -2
  end
elseif expectedBytes == '' or candidateBytes == '' then
  return -2
end

local currentBytes = redis.call('GET', KEYS[1])
if currentBytes ~= false and redis.call('PTTL', KEYS[1]) ~= -1 then
  return -3
end

if mode == 'VERIFY' then
  if currentBytes == expectedBytes then
    return 0
  end
  return -1
end

if currentBytes == candidateBytes then
  return 0
end

if mode == 'ABSENT' then
  if currentBytes ~= false then
    return -1
  end
  redis.call('SET', KEYS[1], candidateBytes)
  return 1
end

if currentBytes ~= expectedBytes then
  return -1
end

redis.call('SET', KEYS[1], candidateBytes)
return 1
