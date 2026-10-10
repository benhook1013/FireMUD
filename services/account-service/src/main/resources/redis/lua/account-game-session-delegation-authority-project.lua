local MAX_RECORD_BYTES = 8192
-- Issuer only. Account source projections use account.account-generation-projection.v1.

local function exact_fields(value, required, optional)
  if type(value) ~= 'table' then return false end
  local allowed = {}
  for _, field in ipairs(required) do allowed[field] = true end
  for _, field in ipairs(optional or {}) do allowed[field] = true end
  for field, _ in pairs(value) do
    if type(field) ~= 'string' or not allowed[field] then return false end
  end
  for _, field in ipairs(required) do
    if value[field] == nil then return false end
  end
  return true
end

local function positive_decimal(value)
  if type(value) ~= 'string'
      or string.match(value, '^[1-9][0-9]*$') == nil then
    return false
  end
  return true
end

local function nonnegative_decimal(value)
  return type(value) == 'string'
      and (value == '0' or string.match(value, '^[1-9][0-9]*$') ~= nil)
end

local function decrement_decimal(value)
  local digits = {}
  for index = 1, string.len(value) do
    digits[index] = tonumber(string.sub(value, index, index))
  end
  local index = #digits
  while index > 0 and digits[index] == 0 do
    digits[index] = 9
    index = index - 1
  end
  if index == 0 then return nil end
  digits[index] = digits[index] - 1
  local result = ''
  local first = 1
  while first < #digits and digits[first] == 0 do first = first + 1 end
  for position = first, #digits do result = result .. tostring(digits[position]) end
  return result
end

local function generation_greater(left, right)
  if string.len(left) ~= string.len(right) then
    return string.len(left) > string.len(right)
  end
  return left > right
end

local function validate_record(record, key)
  if type(record) ~= 'string' or string.len(record) == 0 or string.len(record) > MAX_RECORD_BYTES then
    return nil
  end
  local ok, value = pcall(cjson.decode, record)
  if not ok or type(value) ~= 'table' then return nil end

  local scope = nil
  local scope_id = nil
  local expected_stream = nil
  local issuer_prefix = 'session:auth:generation:issuer:'
  if string.sub(key, 1, string.len(issuer_prefix)) == issuer_prefix then
    scope = 'issuer'
    scope_id = string.sub(key, string.len(issuer_prefix) + 1)
    if scope_id ~= 'firemud-account-service' then return nil end
    expected_stream = 'account:auth-authority:v1:issuer/' .. scope_id
  else
    return nil
  end

  local required = {
    'schemaVersion', 'issuerId', 'issuerAuthGeneration', 'sourceVersion',
    'outboxStreamKey', 'lastAppliedSourceOutboxSequence'
  }
  local optional = {'lastAppliedSourceEventId', 'lastAppliedSourceEventDigest', 'sourceEvent'}
  if not exact_fields(value, required, optional)
      or value.schemaVersion ~= 'account-auth-issuer-generation-projection/v1'
      or value.issuerId ~= scope_id
      or not positive_decimal(value.issuerAuthGeneration)
      or not positive_decimal(value.sourceVersion)
      or value.outboxStreamKey ~= expected_stream
      or not nonnegative_decimal(value.lastAppliedSourceOutboxSequence) then return nil end
  if value.lastAppliedSourceOutboxSequence == '0' then
    if value.issuerAuthGeneration ~= '1' or value.sourceVersion ~= '1'
        or value.lastAppliedSourceEventId ~= nil or value.lastAppliedSourceEventDigest ~= nil
        or value.sourceEvent ~= nil then return nil end
  else
    if value.issuerAuthGeneration == '1' or value.sourceVersion == '1'
        or type(value.lastAppliedSourceEventId) ~= 'string'
        or string.len(value.lastAppliedSourceEventId) == 0
        or type(value.lastAppliedSourceEventDigest) ~= 'string'
        or string.match(value.lastAppliedSourceEventDigest, '^sha256:[0-9a-f]+$') == nil
        or string.len(value.lastAppliedSourceEventDigest) ~= 71
        or type(value.sourceEvent) ~= 'string' or string.len(value.sourceEvent) == 0 then return nil end
  end

  return value
end

if #KEYS ~= 1 or #ARGV ~= 1 then return -4 end
local key = KEYS[1]
local generation_prefix = 'session:auth:generation:'
if string.len(key) > 256
    or string.sub(key, 1, string.len(generation_prefix)) ~= generation_prefix then return -4 end
local incoming = validate_record(ARGV[1], key)
if not incoming then return -4 end

local current = redis.call('GET', key)
if current then
  local existing = validate_record(current, key)
  if not existing or redis.call('PTTL', key) ~= -1 then return -5 end
  if generation_greater(existing.issuerAuthGeneration, incoming.issuerAuthGeneration) then return -1 end
  if existing.issuerAuthGeneration == incoming.issuerAuthGeneration then
    if current == ARGV[1] then return 0 end
    return -2
  end
end

local stored = redis.call('SET', key, ARGV[1])
if stored ~= 'OK' then return -3 end
return 1
