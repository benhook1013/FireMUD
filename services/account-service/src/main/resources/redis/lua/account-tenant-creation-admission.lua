-- Account-only preliminary receipt. Durable SQL admission is required before dispatch or ACK.
-- Caller time is checked again by the owner after independent readback and at callback boundaries.
if #KEYS ~= 2 or #ARGV ~= 4 then return -1 end
local tokenKey, receiptKey = KEYS[1], KEYS[2]
local mode, expectedActive, receiptBytes, nowText = ARGV[1], ARGV[2], ARGV[3], ARGV[4]
if #tokenKey ~= 83 or not string.match(tokenKey, '^session:auth:token:[0-9a-f]+$')
    or (mode ~= 'INITIAL' and mode ~= 'EXISTING_ONLY')
    or #receiptBytes == 0 or #receiptBytes > 8192 or #expectedActive > 32768
    or not string.match(nowText, '^[1-9][0-9]*$') or #nowText > 16 then return -1 end
local now = tonumber(nowText)
if not now or now > 9007199254740991 then return -1 end
local ok, receipt = pcall(cjson.decode, receiptBytes)
if not ok or type(receipt) ~= 'table' then return -1 end
local fields = {
    schema = true, intentRequestId = true, creationOperationId = true,
    issuanceOperationId = true, actorAccountId = true, targetNamespace = true,
    gameDesignCreationRequestId = true, gameDesignRequestDigest = true, intentDigest = true,
    activeRegistryDigest = true, reauthenticationEvidenceId = true, tokenHash = true,
    tokenCleanupDeadlineEpochMillis = true, tokenExpiresAtEpochMillis = true,
    preparedAtEpochMillis = true, operationDeadlineEpochMillis = true,
    receiptCleanupDeadlineEpochMillis = true
}
local fieldCount = 0
for name,_ in pairs(receipt) do
    if not fields[name] then return -1 end
    fieldCount = fieldCount + 1
end
if fieldCount ~= 17 or receipt.schema ~= 'account-tenant-creation-admission-v1' then return -1 end
local function uuid(value)
    return type(value) == 'string' and #value == 36
        and string.match(value, '^[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+%-[0-9a-f]+$')
        and string.sub(value, 9, 9) == '-' and string.sub(value, 14, 14) == '-'
        and string.sub(value, 19, 19) == '-' and string.sub(value, 24, 24) == '-'
        and value ~= '00000000-0000-0000-0000-000000000000'
end
for _,name in ipairs({'intentRequestId','creationOperationId','issuanceOperationId',
    'actorAccountId','gameDesignCreationRequestId','reauthenticationEvidenceId'}) do
    if not uuid(receipt[name]) then return -1 end
end
local function hex(value, prefix)
    return type(value) == 'string' and #value == #prefix + 64
        and string.sub(value, 1, #prefix) == prefix
        and string.match(string.sub(value, #prefix + 1), '^[0-9a-f]+$')
end
if not hex(receipt.tokenHash, '') or not hex(receipt.activeRegistryDigest, '')
    or not hex(receipt.gameDesignRequestDigest, 'sha256:')
    or not hex(receipt.intentDigest, 'sha256:')
    or type(receipt.targetNamespace) ~= 'string' or #receipt.targetNamespace == 0
    or #receipt.targetNamespace > 63
    or not string.match(receipt.targetNamespace, '^[a-z0-9][a-z0-9%-]*$')
    or not string.match(receipt.targetNamespace, '[a-z0-9]$')
    or receipt.tokenHash ~= string.sub(tokenKey, 20)
    or receiptKey ~= 'session:auth:creation-admission:{' .. tokenKey .. '}:'
        .. receipt.creationOperationId then return -1 end
local function positiveInteger(value)
    return type(value) == 'number' and value > 0 and value <= 9007199254740991
        and math.floor(value) == value
end
for _,name in ipairs({'tokenCleanupDeadlineEpochMillis','tokenExpiresAtEpochMillis',
    'preparedAtEpochMillis','operationDeadlineEpochMillis','receiptCleanupDeadlineEpochMillis'}) do
    if not positiveInteger(receipt[name]) then return -1 end
end
if receipt.tokenExpiresAtEpochMillis % 1000 ~= 0
    or receipt.tokenCleanupDeadlineEpochMillis < receipt.tokenExpiresAtEpochMillis
    or receipt.operationDeadlineEpochMillis <= receipt.preparedAtEpochMillis
    or receipt.operationDeadlineEpochMillis > receipt.tokenExpiresAtEpochMillis
    or receipt.operationDeadlineEpochMillis - receipt.preparedAtEpochMillis > 300000
    or receipt.receiptCleanupDeadlineEpochMillis - receipt.operationDeadlineEpochMillis
        ~= 86400000 then return -1 end
if now < receipt.preparedAtEpochMillis or now >= receipt.operationDeadlineEpochMillis
    or now >= receipt.tokenExpiresAtEpochMillis then return -2 end
local current = redis.call('GET', receiptKey)
if current and current ~= receiptBytes then return -3 end
if current and redis.call('PEXPIRETIME', receiptKey)
    ~= receipt.receiptCleanupDeadlineEpochMillis then return -4 end
if mode == 'EXISTING_ONLY' then
    if #expectedActive ~= 0 then return -1 end
    if not current then return -3 end
else
    if #expectedActive == 0 then return -1 end
    local activeOk, active = pcall(cjson.decode, expectedActive)
    if not activeOk or type(active) ~= 'table' or active.schemaVersion ~= 1
        or active.registryVersion ~= 2 or active.state ~= 'active'
        or active.profile ~= 'control-ui' or active.type ~= 'control-ui'
        or active.audience ~= 'control-ui' or active.issuer ~= 'firemud-account-service'
        or active.globalRoles ~= nil or active.scopedRoles ~= nil
        or active.accountId ~= receipt.actorAccountId
        or active.operationId ~= receipt.issuanceOperationId
        or active.tokenHash ~= receipt.tokenHash or not positiveInteger(active.exp)
        or active.exp > 9007199254740
        or active.exp * 1000 ~= receipt.tokenExpiresAtEpochMillis
        or active.registryCleanupDeadlineEpochMillis
            ~= receipt.tokenCleanupDeadlineEpochMillis then return -1 end
    if redis.call('GET', tokenKey) ~= expectedActive then return -5 end
    if redis.call('PEXPIRETIME', tokenKey) ~= receipt.tokenCleanupDeadlineEpochMillis
        then return -4 end
end
-- Exact-existing retry must WRITE on this physical connection before its WAITAOF.
redis.call('SET', receiptKey, receiptBytes, 'PXAT',
    string.format('%.0f', receipt.receiptCleanupDeadlineEpochMillis))
if current then return 0 end
return 1
