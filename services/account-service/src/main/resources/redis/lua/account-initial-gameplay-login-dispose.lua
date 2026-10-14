-- One exact original hash key; permanent denied projection while SQL retention is held.
if #KEYS ~= 1 or #ARGV ~= 3 then return -4 end
local key = KEYS[1]
if not string.match(key, '^session:auth:token:[0-9a-f]+$')
    or string.len(key) ~= string.len('session:auth:token:') + 64 then return -4 end
local pending, active, tombstone = ARGV[1], ARGV[2], ARGV[3]
if not pending or #pending == 0 or #pending > 16384
    or not active or #active == 0 or #active > 16384
    or not tombstone or #tombstone == 0 or #tombstone > 4096 then return -4 end
local versioned, versions = string.gsub(pending, '"registryVersion":1', '"registryVersion":2')
local expected, states = string.gsub(versioned, '"state":"pending"', '"state":"active"')
if versions ~= 1 or states ~= 1 or expected ~= active then return -4 end
local current = redis.call('GET', key)
if current and current ~= pending and current ~= active and current ~= tombstone then return -1 end
-- Absence is never itself completion: reassert a durable denied marker even after expiry/reset.
redis.call('SET', key, tombstone)
if current == tombstone then return 0 end
return 1
