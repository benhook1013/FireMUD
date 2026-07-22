local queue = KEYS[1]
local pending = KEYS[2]
local lease = KEYS[3]
if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end
local max = tonumber(ARGV[2])
local moved = 0
while moved < max do
  local cmd = redis.call('LPOP', queue)
  if not cmd then
    break
  end
  redis.call('RPUSH', pending, cmd)
  moved = moved + 1
end
return moved
