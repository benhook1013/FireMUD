-- Exact-original negative closure. This is not authenticated session state or admission proof.
-- The SQL owner must first irreversibly select abandonment; external reset/restore exclusion
-- remains held through physical readback and the purpose-specific Account continuation.
-- Retain the no-TTL session fence: deleting it would re-enable an already-issued create EVAL.
local function uuid(value)
  return type(value) == 'string' and #value == 36
      and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$')
      and value == string.lower(value)
      and value ~= '00000000-0000-0000-0000-000000000000'
end

if #KEYS ~= 4 or #ARGV ~= 12 then return -2 end
local purpose = ARGV[1]
local tenantId, accountId, namespaceId, characterId = ARGV[2], ARGV[3], ARGV[4], ARGV[5]
local gameInstanceId, sessionId, generation, tag = ARGV[6], ARGV[7], ARGV[8], ARGV[9]
local candidate, abandonmentId, digest = ARGV[10], ARGV[11], ARGV[12]
if purpose ~= 'original-new-never-admitted-closure/v1'
    or not uuid(tenantId) or not uuid(accountId) or not uuid(namespaceId)
    or not uuid(characterId) or not uuid(gameInstanceId) or not uuid(sessionId)
    or not uuid(abandonmentId)
    or type(generation) ~= 'string' or #generation == 0 or #generation > 256
    or not string.match(generation, '^[1-9][0-9]*$')
    or type(tag) ~= 'string' or #tag == 0 or #tag > 512
    or string.find(tag, '{', 1, true) or string.find(tag, '}', 1, true)
    or type(candidate) ~= 'string' or #candidate == 0 or #candidate > 524288
    or type(digest) ~= 'string' or #digest ~= 71
    or string.sub(digest, 1, 7) ~= 'sha256:'
    or not string.match(string.sub(digest, 8), '^[0-9a-f]+$') then return -2 end

if KEYS[1] ~= 'session:game:{' .. tag .. '}:' .. gameInstanceId .. ':' .. sessionId
    or KEYS[2] ~= 'session:game:index:character:{' .. tag .. '}:' .. namespaceId .. ':' .. characterId
    or KEYS[3] ~= 'session:game:index:account-tenant:{' .. tag .. '}:' .. accountId
    or KEYS[4] ~= 'session:game:index:tenant:{' .. tag .. '}' then return -2 end

-- Validate all types and expiry before any mutation. Foreign or partial state is retained.
local types = {}
for index = 1, 4 do
  types[index] = redis.call('TYPE', KEYS[index]).ok
  local expected = index <= 2 and 'string' or 'set'
  if types[index] ~= 'none' and types[index] ~= expected then return -1 end
  if redis.call('PTTL', KEYS[index]) ~= (types[index] == 'none' and -2 or -1) then return -1 end
end
local session = types[1] == 'string' and redis.call('GET', KEYS[1]) or false
local character = types[2] == 'string' and redis.call('GET', KEYS[2]) or false
local expectedCharacter = '{"sessionId":"' .. sessionId .. '","gameInstanceId":"' .. gameInstanceId
    .. '","bindingGeneration":"' .. generation .. '"}'
local accountMember = redis.call('SISMEMBER', KEYS[3], sessionId)
local tenantMember = redis.call('SISMEMBER', KEYS[4], sessionId)
local fence = purpose .. ':' .. abandonmentId .. ':' .. digest

if session == fence then
  if character == expectedCharacter or accountMember ~= 0 or tenantMember ~= 0 then return -1 end
  return 2
end
if not session then
  if character == expectedCharacter or accountMember ~= 0 or tenantMember ~= 0 then return -1 end
  -- A foreign character controller and unrelated set members are outside this attempt.
  redis.call('SET', KEYS[1], fence)
  return 1
end
if session ~= candidate or character ~= expectedCharacter
    or accountMember ~= 1 or tenantMember ~= 1 then return -1 end

-- The candidate and each removed projection are the exact atomic installation postimage.
redis.call('SET', KEYS[1], fence)
redis.call('DEL', KEYS[2])
redis.call('SREM', KEYS[3], sessionId)
redis.call('SREM', KEYS[4], sessionId)
return 1
