local pending = KEYS[1]
local queue = KEYS[2]
local lease = KEYS[3]
if redis.call('GET', lease) ~= ARGV[1] then
  return -1
end
while true do
  local cmd = redis.call('RPOP', pending)
  if not cmd then
    break
  end
  redis.call('LPUSH', queue, cmd)
end
return 1
