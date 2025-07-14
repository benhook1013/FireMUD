local queue = KEYS[1]
local pending = KEYS[2]
local max = tonumber(ARGV[1])
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
