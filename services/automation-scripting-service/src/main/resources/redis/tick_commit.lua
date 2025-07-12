local pending = KEYS[1]
local processed = 0
while true do
  local cmd = redis.call('LPOP', pending)
  if not cmd then
    break
  end
  processed = processed + 1
end
return processed
