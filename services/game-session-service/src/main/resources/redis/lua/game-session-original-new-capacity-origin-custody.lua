-- Unregistered recovery custody primitive. This key is NOT capacity outcome evidence.
-- Every mutation is same-slot and exact-fenced. SQL authenticates origin, custody and
-- transfer receipts before submission; no SQL lock is held across this script.
-- KEYS[1]: persistent origin key. ARGV: action, origin digest, old owner, old fence,
-- new owner, new fence, captured state, operation deadline. TRANSFER is callable only for a retained captured transfer.
local function owner(v)
  return type(v) == 'string' and #v > 0 and #v <= 120
      and string.match(v, '^[A-Za-z0-9._:-]+$') ~= nil
end
local function positive(v)
  return type(v) == 'string' and #v > 0 and #v <= 19
      and string.match(v, '^[1-9][0-9]*$') ~= nil
      and (#v < 19 or v <= '9223372036854775807')
end
-- Decimal comparison avoids rounding 64-bit durable SQL fences through Lua doubles.
local function greater(a, b)
  return #a > #b or (#a == #b and a > b)
end
if #KEYS ~= 1 or #ARGV ~= 8 then return -2 end
local action, digest, oldOwner, oldFence, newOwner, newFence, expectedState, deadlineText = unpack(ARGV)
if type(digest) ~= 'string' or #digest ~= 71
    or not string.match(digest, '^sha256:[0-9a-f]+$')
    or not owner(oldOwner) or not positive(oldFence) then return -2 end
if action ~= 'INIT' and action ~= 'DISPATCH' and action ~= 'CLOSE_CUSTODY'
    and action ~= 'DENY' and action ~= 'TRANSFER' then return -2 end
if action == 'TRANSFER' then
  if not owner(newOwner) or not positive(newFence) or not greater(newFence, oldFence) then return -2 end
elseif newOwner ~= oldOwner or newFence ~= oldFence then return -2 end
if expectedState ~= 'OPEN' and expectedState ~= 'DISPATCH_CLAIMED'
    and expectedState ~= 'CLOSURE_CLAIMED' and expectedState ~= 'DENIED' then return -2 end
local deadline = tonumber(deadlineText)
if not positive(deadlineText) or #deadlineText > 16 or not deadline
    or deadline > 9007199254740991 or string.format('%.0f', deadline) ~= deadlineText then return -2 end
local function timely()
  local now = redis.call('TIME')
  return tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) < deadline
end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind == 'none' then
  -- Only the successful SQL origin inserter may make the first physical INIT write.
  -- Transfer and every replay require the already-installed persistent marker.
  if action ~= 'INIT' then return -1 end
  if action == 'INIT' and expectedState ~= 'OPEN' then return -2 end
  if not timely() then return -1 end
  redis.call('HSET', KEYS[1], 'origin', digest, 'owner', newOwner, 'fence', newFence, 'state', expectedState)
  return 1
end
if kind ~= 'hash' or redis.call('PTTL', KEYS[1]) ~= -1
    or redis.call('HLEN', KEYS[1]) ~= 4
    or redis.call('HGET', KEYS[1], 'origin') ~= digest then return -1 end
local currentOwner = redis.call('HGET', KEYS[1], 'owner')
local currentFence = redis.call('HGET', KEYS[1], 'fence')
local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'OPEN' and state ~= 'DISPATCH_CLAIMED'
    and state ~= 'CLOSURE_CLAIMED' and state ~= 'DENIED' then return -1 end
if action == 'TRANSFER' and currentOwner == newOwner and currentFence == newFence then
  if state ~= expectedState then return -1 end
  return 2
end
if currentOwner ~= oldOwner or currentFence ~= oldFence then return -1 end
if action == 'TRANSFER' then
  if state ~= expectedState and state ~= 'OPEN' then return -1 end
  if expectedState == 'OPEN' and state ~= 'OPEN' then return -1 end
  if not timely() then return -1 end
  redis.call('HSET', KEYS[1], 'owner', newOwner, 'fence', newFence, 'state', expectedState)
  return 1
end
if action == 'INIT' then
  if expectedState ~= 'OPEN' or state ~= 'OPEN' then return -1 end
  return 2
end
local target = action == 'DISPATCH' and 'DISPATCH_CLAIMED'
    or action == 'CLOSE_CUSTODY' and 'CLOSURE_CLAIMED' or 'DENIED'
if expectedState ~= target then return -2 end
if state == target then return 2 end
if state ~= 'OPEN' then return -1 end
if not timely() then return -1 end
redis.call('HSET', KEYS[1], 'state', target)
return 1
