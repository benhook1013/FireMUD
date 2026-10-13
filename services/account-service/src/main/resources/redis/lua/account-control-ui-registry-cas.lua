-- Account-owned control-ui only. The client separately proves owner COMMITTED before activation.
if #KEYS ~= 1 or #ARGV ~= 3 then return -1 end
local key, expected, replacement, expiry = KEYS[1], ARGV[1], ARGV[2], ARGV[3]
if not string.match(key, '^session:auth:token:[0-9a-f]+$') or #key ~= 83
    or #replacement == 0 or #replacement > 32768 or #expected > 32768
    or not string.match(expiry, '^[1-9][0-9]*$') or #expiry > 16 then return -1 end
local deadline = tonumber(expiry)
if not deadline or deadline > 9007199254740991 then return -1 end
local now = redis.call('TIME')
if deadline <= tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000) then return -2 end
local ok, nextRecord = pcall(cjson.decode, replacement)
if not ok or type(nextRecord) ~= 'table' or nextRecord.schemaVersion ~= 1
    or nextRecord.profile ~= 'control-ui' or nextRecord.type ~= 'control-ui'
    or nextRecord.audience ~= 'control-ui' or nextRecord.issuer ~= 'firemud-account-service'
    or nextRecord.tokenHash ~= string.sub(key, 20)
    or nextRecord.globalRoles ~= nil or nextRecord.scopedRoles ~= nil
    or nextRecord.exp * 1000 ~= deadline then return -1 end
local current = redis.call('GET', key)
if current == replacement then
    if redis.call('PEXPIRETIME', key) ~= deadline then return -3 end
    -- Reassert identical bytes/deadline on this physical connection so its WAITAOF covers retry.
    redis.call('SET', key, replacement, 'PXAT', expiry)
    return 0
end
if expected == '' then
    if current then return -4 end
    if nextRecord.state ~= 'pending' or nextRecord.registryVersion ~= 1 then return -1 end
else
    if not current or current ~= expected then return -4 end
    if redis.call('PEXPIRETIME', key) ~= deadline then return -3 end
    local priorOk, prior = pcall(cjson.decode, expected)
    if not priorOk then return -1 end
    local activation = prior.state == 'pending' and prior.registryVersion == 1
        and nextRecord.state == 'active' and nextRecord.registryVersion == 2
    local revocation = ((prior.state == 'pending' and prior.registryVersion == 1)
        or (prior.state == 'active' and prior.registryVersion == 2))
        and nextRecord.state == 'revoked' and nextRecord.registryVersion == 3
    if not activation and not revocation then return -1 end
    local function equal(a, b)
        if type(a) ~= type(b) then return false end
        if type(a) ~= 'table' then return a == b end
        for k,v in pairs(a) do if not equal(v,b[k]) then return false end end
        for k,_ in pairs(b) do if a[k] == nil then return false end end
        return true
    end
    prior.state, prior.registryVersion = nil, nil
    nextRecord.state, nextRecord.registryVersion = nil, nil
    if not equal(prior,nextRecord) then return -1 end
end
redis.call('SET', key, replacement, 'PXAT', expiry)
return 1
