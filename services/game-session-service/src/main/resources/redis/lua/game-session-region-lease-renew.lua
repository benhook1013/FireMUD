-- Extends only the exact opaque liveness token and never advances the durable executor fence.
local key = KEYS[1]
if #KEYS ~= 1 or #ARGV ~= 3 or ARGV[1] ~= 'gs_region_lease/v1' then return -1 end
local token = ARGV[2]
local ttl = ARGV[3]
if string.len(token) ~= 36
  or string.sub(token, 9, 9) ~= '-'
  or string.sub(token, 14, 14) ~= '-'
  or string.sub(token, 19, 19) ~= '-'
  or string.sub(token, 24, 24) ~= '-'
  or not string.match(string.gsub(token, '%-', ''), '^[0-9a-f]+$') then return -1 end
if not string.match(ttl, '^[1-9][0-9]*$') or string.len(ttl) > 19
  or (string.len(ttl) == 19 and ttl > '9223372036854775807') then return -1 end
local keyType = redis.call('TYPE', key).ok
if keyType ~= 'string' or redis.call('PTTL', key) <= 0
  or redis.call('GET', key) ~= token then return 0 end
redis.call('PEXPIRE', key, ttl)
return 1
