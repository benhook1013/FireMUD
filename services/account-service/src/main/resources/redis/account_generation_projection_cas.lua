if #KEYS ~= 1 or #ARGV ~= 3 then
  return 'INVALID'
end

local mode = ARGV[1]
local expectedBytes = ARGV[2]
local candidateBytes = ARGV[3]

if mode ~= 'ABSENT' and mode ~= 'PRESENT' and mode ~= 'VERIFY' then
  return 'INVALID'
end

if mode == 'VERIFY' then
  if expectedBytes == '' or candidateBytes ~= '' then
    return 'INVALID'
  end
elseif mode == 'ABSENT' then
  if expectedBytes ~= '' or candidateBytes == '' then
    return 'INVALID'
  end
elseif expectedBytes == '' or candidateBytes == '' then
  return 'INVALID'
end

local currentBytes = redis.call('GET', KEYS[1])
if currentBytes ~= false and redis.call('PTTL', KEYS[1]) ~= -1 then
  return 'TTL_PRESENT'
end

if mode == 'VERIFY' then
  if currentBytes == expectedBytes then
    return 'REPLAY'
  end
  return 'STALE'
end

if currentBytes == candidateBytes then
  return 'REPLAY'
end

if mode == 'ABSENT' then
  if currentBytes ~= false then
    return 'STALE'
  end
  redis.call('SET', KEYS[1], candidateBytes)
  return 'APPLIED'
end

if currentBytes ~= expectedBytes then
  return 'STALE'
end

redis.call('SET', KEYS[1], candidateBytes)
return 'APPLIED'
