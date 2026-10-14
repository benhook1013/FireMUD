-- Owner-only compare mutation. No context creation, expiry, or authenticated gameplay admission.
-- KEYS[1] exact tenantless original context. ARGV operation, exact prior bytes, exact desired bytes.
local prefix = 'session:game:initial-context:v1:'
local maxBytes = 32768
local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-4%x%x%x%-[89ab]%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
end
local function positive(value)
  if type(value) ~= 'string' or not string.match(value, '^[1-9][0-9]*$') or #value > 19 then return false end
  return #value < 19 or value <= '9223372036854775807'
end
local function envelope(bytes)
  if type(bytes) ~= 'string' or #bytes == 0 or #bytes > maxBytes then return nil end
  local ok, value = pcall(cjson.decode, bytes)
  if not ok or type(value) ~= 'table' then return nil end
  local count = 0
  for name, _ in pairs(value) do
    if name ~= 'schemaVersion' and name ~= 'state' and name ~= 'requestId'
        and name ~= 'callerContextId' and name ~= 'expectedAttemptFence'
        and name ~= 'accountEvidence' then return nil end
    count = count + 1
  end
  if value.schemaVersion ~= 1 or not uuid(value.requestId) or not uuid(value.callerContextId)
      or not positive(value.expectedAttemptFence) then return nil end
  if value.state == 'INSTALLED' then
    if count ~= 6 or type(value.accountEvidence) ~= 'table' then return nil end
    local evidence = value.accountEvidence
    local allowed = {accountId=true,requestId=true,callerContextId=true,tokenJti=true,
      tokenSha256=true,tokenProfile=true,issuedAtEpochSecond=true,notBeforeEpochSecond=true,
      expiresAtEpochSecond=true,tokenGeneration=true,issuanceFence=true,
      authorityTupleCanonicalJson=true,outboxCheckpointsCanonicalJson=true,
      outboxSourceEventEvidenceCanonicalJson=true}
    local evidenceCount = 0
    for name, _ in pairs(evidence) do
      if not allowed[name] then return nil end
      evidenceCount = evidenceCount + 1
    end
    if evidenceCount ~= 14 or evidence.requestId ~= value.requestId
        or evidence.callerContextId ~= value.callerContextId then return nil end
  elseif value.state == 'PENDING' or value.state == 'CLOSED' then
    if count ~= 5 then return nil end
  else return nil end
  return value
end
if #KEYS ~= 1 or #ARGV ~= 3 then return -2 end
local operation = ARGV[1]
if operation ~= 'INSTALL' and operation ~= 'CLOSE' then return -2 end
local before = envelope(ARGV[2])
local after = envelope(ARGV[3])
if not before or not after or KEYS[1] ~= prefix .. before.callerContextId
    or before.requestId ~= after.requestId or before.callerContextId ~= after.callerContextId
    or before.expectedAttemptFence ~= after.expectedAttemptFence then return -2 end
if operation == 'INSTALL' then
  if after.state ~= 'INSTALLED' or (before.state ~= 'PENDING'
      and not (before.state == 'INSTALLED' and ARGV[2] == ARGV[3])) then return -2 end
else
  if after.state ~= 'CLOSED' then return -2 end
end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'string' then return -1 end
local current = redis.call('GET', KEYS[1])
if current ~= ARGV[2] then return -1 end
-- Expiry-bearing physical state is unknown, never adopted by stripping its TTL.
if redis.call('PTTL', KEYS[1]) ~= -1 then return -1 end
-- Reasserting exact CLOSED bytes is a fresh physical write for this invocation's durability ACK.
redis.call('SET', KEYS[1], ARGV[3])
return 1
