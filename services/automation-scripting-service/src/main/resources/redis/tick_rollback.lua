local pending = KEYS[1]
local queue = KEYS[2]
while true do
  local cmd = redis.call('RPOP', pending)
  if not cmd then
    break
  end
  redis.call('LPUSH', queue, cmd)
end
return 1
