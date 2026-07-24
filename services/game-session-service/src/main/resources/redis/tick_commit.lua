local pending = KEYS[1]
local lease = KEYS[2]
if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end
local processed = 0
while true do
  local cmd = redis.call('LPOP', pending)
  if not cmd then
    break
  end
  processed = processed + 1
end
return processed
