-- Binding-local ADD_OR_RETAIN on the one untagged account index key.
-- KEYS[1] account key; ARGV carries the pinned SQL operation tuple and exact member.
local prefix = 'session:game:index:account:'
local memberPrefix = 'accountIndexMember/v1:'
local maxRefBytes = 6144
local maxMemberBytes = 8192
local maxDecimalBytes = 256

local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
      and value ~= '00000000-0000-0000-0000-000000000000'
end

local function positive(value)
  if type(value) ~= 'string' or #value == 0 or #value > maxDecimalBytes
      or not string.match(value, '^[1-9][0-9]*$') then return false end
  return true
end

local function nonnegative(value)
  if value == '0' then return true end
  return positive(value)
end

local function readSegment(bytes, offset)
  local start = offset
  while offset <= #bytes do
    local char = string.byte(bytes, offset)
    if char < 48 or char > 57 then break end
    offset = offset + 1
  end
  if offset == start or offset > #bytes or string.byte(bytes, offset) ~= 58 then return nil end
  if offset - start > 1 and string.byte(bytes, start) == 48 then return nil end
  local lengthText = string.sub(bytes, start, offset - 1)
  if #lengthText > 5 then return nil end
  local length = tonumber(lengthText)
  if not length or length < 0 or length > maxRefBytes or offset + length > #bytes then return nil end
  local valueStart = offset + 1
  local valueEnd = offset + length
  return string.sub(bytes, valueStart, valueEnd), valueEnd + 1
end

local function bindingRef(bytes, expectedTenantId)
  if type(bytes) ~= 'string' or #bytes == 0 or #bytes > maxRefBytes then return false end
  local domain, offset = readSegment(bytes, 1)
  if domain ~= 'bindingRef/v1' or not offset then return false end
  local tenant, afterTenant = readSegment(bytes, offset)
  if not tenant or not afterTenant then return false end
  local gameInstance, afterInstance = readSegment(bytes, afterTenant)
  if not gameInstance or not afterInstance then return false end
  local session, afterSession = readSegment(bytes, afterInstance)
  if not session or not afterSession or afterSession ~= #bytes + 1 then return false end
  return tenant == expectedTenantId and uuid(tenant) and uuid(gameInstance) and uuid(session)
end

local function base64url(bytes)
  local alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
  local output = {}
  for index = 1, #bytes, 3 do
    local first = string.byte(bytes, index)
    local second = string.byte(bytes, index + 1)
    local third = string.byte(bytes, index + 2)
    local combined = first * 65536 + (second or 0) * 256 + (third or 0)
    local a = math.floor(combined / 262144) % 64 + 1
    local b = math.floor(combined / 4096) % 64 + 1
    local c = math.floor(combined / 64) % 64 + 1
    local d = combined % 64 + 1
    output[#output + 1] = string.sub(alphabet, a, a)
    output[#output + 1] = string.sub(alphabet, b, b)
    if second then output[#output + 1] = string.sub(alphabet, c, c) end
    if third then output[#output + 1] = string.sub(alphabet, d, d) end
  end
  return table.concat(output)
end

if #KEYS ~= 1 or #ARGV ~= 12 then return -2 end
local accountId = ARGV[1]
local tenantId = ARGV[2]
local refBytes = ARGV[3]
local generation = ARGV[4]
local accountIndexFence = ARGV[5]
local member = ARGV[6]
local transitionId = ARGV[7]
local operationId = ARGV[8]
local operationFence = ARGV[9]
local coverageFence = ARGV[10]
local snapshotRevision = ARGV[11]
local accountControlEpoch = ARGV[12]

if not uuid(accountId) or not uuid(tenantId) or not bindingRef(refBytes, tenantId)
    or not positive(generation) or not uuid(accountIndexFence)
    or not uuid(transitionId) or not uuid(operationId) or not positive(operationFence)
    or not uuid(coverageFence) or not positive(snapshotRevision)
    or not nonnegative(accountControlEpoch)
    or operationId == transitionId or coverageFence == operationId
    or coverageFence == transitionId or coverageFence == accountIndexFence then return -2 end

local expectedMember = memberPrefix .. base64url(refBytes) .. ':' .. generation .. ':' .. accountIndexFence
if type(member) ~= 'string' or #member == 0 or #member > maxMemberBytes
    or member ~= expectedMember then return -2 end
if KEYS[1] ~= prefix .. accountId then return -2 end

local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'set' then return -1 end
local ttl = redis.call('PTTL', KEYS[1])
if kind == 'none' and ttl ~= -2 or kind == 'set' and ttl ~= -1 then return -1 end

-- SADD is idempotent and adds only this exact generation/fence-qualified member.
-- It never removes, replaces, or enumerates other account members.
local added = redis.call('SADD', KEYS[1], member)
if added == 1 then return 1 end
return 2
