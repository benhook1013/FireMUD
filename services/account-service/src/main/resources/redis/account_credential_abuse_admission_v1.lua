#!lua flags=no-cluster
-- One declared key only. This disposable policy state never grants identity or durable authority.
local function unavailable() return {'UNAVAILABLE', 0, 0} end
local function integer(s)
  if type(s) ~= 'string' or #s < 1 or #s > 16 or not string.match(s, '^%d+$') then return nil end
  local n = tonumber(s)
  if not n or n < 0 or n > 9007199254740991 or string.format('%.0f', n) ~= s then return nil end
  return n
end
local function field(info, name)
  return string.match(info, '\n' .. name .. ':([^\r\n]+)')
end
if #KEYS ~= 1 or #ARGV ~= 12 or #KEYS[1] > 320 then return unavailable() end
local now = integer(ARGV[1])
local expiry = integer(ARGV[2])
local skew = integer(ARGV[3])
local keyLimit = integer(ARGV[4])
local memoryLimit = integer(ARGV[5])
local bucketLimit = integer(ARGV[6])
local counterLimit = integer(ARGV[7])
local admissionLimit = integer(ARGV[8])
local windowMillis = integer(ARGV[11])
local ttlMillis = integer(ARGV[12])
local windowNumber = integer(string.match(KEYS[1], ':([0-9]+)$'))
if not now or not expiry or not skew or not keyLimit or not memoryLimit or not bucketLimit
    or not counterLimit or not admissionLimit or skew < 1 or keyLimit < 1 or bucketLimit < 1
    or bucketLimit >= memoryLimit or counterLimit < 1 or admissionLimit < 1
    or admissionLimit > counterLimit or not windowMillis or windowMillis < 1
    or not ttlMillis or ttlMillis < windowMillis or not windowNumber
    or windowNumber * windowMillis > 9007199254740991
    or windowNumber * windowMillis + ttlMillis ~= expiry then return unavailable() end
local op = ARGV[9]
local witness = ARGV[10]
if not string.match(KEYS[1], '^ratelimit:account%-credential:v1:pressure:')
    or (op ~= 'ADMIT' and op ~= 'FAIL') or witness ~= 'pressure-v1' then return unavailable() end
-- WHOAMI is NOSCRIPT in Redis 8.10.3; the owner checks it on this same pinned connection.
local info = redis.call('INFO', 'server', 'cluster', 'replication', 'memory')
if #info > 32768 or field(info, 'redis_version') ~= '8.10.3'
    or field(info, 'cluster_enabled') ~= '0' or field(info, 'role') ~= 'master' then return unavailable() end
local used = integer(field(info, 'used_memory'))
local maxmemory = integer(field(info, 'maxmemory'))
local eviction = field(info, 'maxmemory_policy')
if not used or not maxmemory or maxmemory < 1 or maxmemory > memoryLimit or used > memoryLimit
    or (eviction ~= 'allkeys-lru' and eviction ~= 'noeviction') then return unavailable() end
local time = redis.call('TIME')
local seconds = integer(time[1])
local micros = integer(time[2])
if not seconds or not micros or micros > 999999 then return unavailable() end
local serverNow = seconds * 1000 + math.floor(micros / 1000)
if serverNow > 9007199254740991 or math.abs(serverNow - now) > skew or expiry <= serverNow then
  return unavailable()
end
-- Preflight cleanup/write permissions before provisional state can exist.
if not redis.acl_check_cmd('PEXPIRETIME', KEYS[1])
    or not redis.acl_check_cmd('DEL', KEYS[1])
    or not redis.acl_check_cmd('SET', KEYS[1], 'probe', 'PXAT', ARGV[2]) then return unavailable() end
-- Capability is required on an empty database too, before any provisional mutation.
local retainedExpiry = redis.call('PEXPIRETIME', KEYS[1])
local measuredBefore = redis.call('MEMORY', 'USAGE', KEYS[1])
local size = redis.call('STRLEN', KEYS[1])
if size > 173 then return unavailable() end
local old = redis.call('GET', KEYS[1])
-- A skewed caller cannot create an early future window or revive an old missing window.
-- Existing failure accounting may consume its original retained window without extending it.
if (not old or op ~= 'FAIL') and windowNumber ~= math.floor(serverNow / windowMillis) then return unavailable() end
local count1 = 0
local count2 = 0
if old then
  local savedWitness, first, second = string.match(old, '^v1|([^|]+)|(%d+)|(%d+)$')
  if savedWitness ~= witness or #first ~= 16 or #second ~= 16 then return unavailable() end
  count1 = tonumber(first)
  count2 = tonumber(second)
  if not count1 or not count2 or count1 > counterLimit or count2 > counterLimit
      or retainedExpiry ~= expiry then return unavailable() end
  if not measuredBefore or measuredBefore > bucketLimit then return unavailable() end
end
local actualKeys = redis.call('DBSIZE')
if actualKeys > keyLimit then return unavailable() end
if not old and (actualKeys >= keyLimit or used > memoryLimit - bucketLimit) then return unavailable() end
if op == 'ADMIT' then
  if count1 >= admissionLimit then return unavailable() end
  count1 = count1 + 1
else
  count2 = math.min(counterLimit, count2 + 1)
end
if old and op == 'READ' then return {'OBSERVED', count1, count2} end
local value = 'v1|' .. witness .. '|' .. string.format('%016.0f', count1)
    .. '|' .. string.format('%016.0f', count2)
-- PXAT preserves the original absolute expiration, including repeated failures.
redis.call('SET', KEYS[1], value, 'PXAT', ARGV[2])
local footprint = redis.call('MEMORY', 'USAGE', KEYS[1])
local afterInfo = redis.call('INFO', 'memory')
local afterUsed = #afterInfo <= 16384 and integer(field(afterInfo, 'used_memory')) or nil
if not footprint or footprint > bucketLimit or not afterUsed or afterUsed > memoryLimit then
  if not old then
    redis.call('DEL', KEYS[1])
    return {'CLEANED_UNAVAILABLE', 0, 0}
  end
  -- A conservative counter may have advanced. Never refund it or authorize authentication.
  return {'ACCOUNTED_UNAVAILABLE', 0, 0}
end
return {'APPLIED', count1, count2}
