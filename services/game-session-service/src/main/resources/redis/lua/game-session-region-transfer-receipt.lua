-- Registered forward-only transfer receipt. Historical UUID-only dispatch is not upgraded.
local function uuid(value)
  return type(value) == 'string' and #value == 36 and value == string.lower(value)
    and value ~= '00000000-0000-0000-0000-000000000000'
    and string.match(value, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
end
local function positive(value)
  return type(value) == 'string' and #value > 0 and #value <= 256
    and string.match(value, '^[1-9][0-9]*$') ~= nil
end
local function exactNumber(value)
  if not positive(value) or #value > 16 then return nil end
  local number = tonumber(value)
  if not number or number > 9007199254740991 or string.format('%.0f', number) ~= value then return nil end
  return number
end
local function decode(bytes, maximum)
  if type(bytes) ~= 'string' or #bytes == 0 or #bytes > maximum then return nil end
  local ok, value = pcall(cjson.decode, bytes)
  if not ok or type(value) ~= 'table' then return nil end
  return value
end
if #KEYS ~= 2 or #ARGV ~= 9 then return -2 end
local action, binding, digest, dispatch, incarnation = ARGV[1],ARGV[2],ARGV[3],ARGV[4],ARGV[5]
local nonce, mutateUntil = ARGV[6],tonumber(ARGV[7])
if type(binding)~='string' or #binding==0 or #binding>1048576
 or not uuid(nonce) or not mutateUntil or mutateUntil~=math.floor(mutateUntil)
 or mutateUntil<=0 or mutateUntil>9007199254740991 then return {-2,'',''} end
if #digest~=71 or string.sub(digest,1,7)~='sha256:' or not string.match(string.sub(digest,8),'^[0-9a-f]+$')
 or #incarnation~=71 or string.sub(incarnation,1,7)~='sha256:'
 or not string.match(string.sub(incarnation,8),'^[0-9a-f]+$')
 or #dispatch~=36 or not string.match(dispatch,'^[0-9a-f%-]+$') then return -2 end
local time=redis.call('TIME')
local seconds,micros=tonumber(time[1]),tonumber(time[2])
if not seconds or not micros or seconds<=0 or micros<0 or micros>=1000000
 or seconds>math.floor((9007199254740991-999)/1000) then return -1 end
local now=seconds*1000+math.floor(micros/1000)
local capacity = decode(binding, 1048576)
if not capacity or capacity.schema ~= 'gs-original-new-capacity-execution/v1'
 or not uuid(capacity.admissionAttemptId) or not uuid(capacity.tenantId)
 or capacity.tenantId ~= ARGV[9] or not uuid(dispatch)
 or not string.match(ARGV[8], '^g[1-9][0-9]*%-[0-9a-f]+$') or #ARGV[8]>44 then return -2 end
local slot='{'..ARGV[8]..'}'
if KEYS[1]~='session:game:capacity-execution:'..slot..':'..capacity.admissionAttemptId
 or KEYS[2]~='session:game:capacity-transfer-receipt:'..slot..':'..capacity.admissionAttemptId then return -2 end
local kind=redis.call('TYPE',KEYS[2]).ok
if kind=='none' then
 if action~='PREPARE' or now>=mutateUntil then return {-1,'',''} end
 -- A nonce has exactly one transport submission. No clock wait or replay permission.
 redis.call('HSET',KEYS[2],'digest',digest,'dispatch',dispatch,'incarnation',incarnation,
  'nonce',nonce,'state','PREPARED')
 return {1,nonce,'PREPARED'}
end
if kind~='hash' or redis.call('PTTL',KEYS[2])~=-1 or redis.call('HLEN',KEYS[2])~=5
 or redis.call('HGET',KEYS[2],'digest')~=digest or redis.call('HGET',KEYS[2],'dispatch')~=dispatch
 or redis.call('HGET',KEYS[2],'incarnation')~=incarnation then return {-1,'',''} end
local state=redis.call('HGET',KEYS[2],'state')
local winner=redis.call('HGET',KEYS[2],'nonce')
if not uuid(winner) or (state~='PREPARED' and state~='APPLIED') then return {-1,'',''} end
if action=='PREPARE' then
 if now>=mutateUntil then return {-1,'',''} end
 return {1,winner,state}
end
if action=='READ_PREPARED' then
 if state~='PREPARED' then return {-1,'',''} end
 return {1,winner,state}
end
if winner~=nonce then return {-1,'',''} end
if action~='APPLY' and action~='OBSERVE' then return {-2,'',''} end
local target=redis.call('TYPE',KEYS[1]).ok
local function exactOutcome()
 if target~='hash' or redis.call('PTTL',KEYS[1])~=-1
  or redis.call('HGET',KEYS[1],'binding')~=binding or redis.call('HGET',KEYS[1],'digest')~=digest then return -1 end
 local phase,fields=redis.call('HGET',KEYS[1],'state'),redis.call('HLEN',KEYS[1])
 if phase=='FENCED_INDETERMINATE' and fields==4
  and redis.call('HGET',KEYS[1],'fenceDispatchId')==dispatch then return 8 end
 if phase=='CLOSED' and fields==3 then return 7 end
 if phase=='COMMITTED' and fields==6 then
  local sha=redis.call('HGET',KEYS[1],'sessionSha1')
  local at=exactNumber(redis.call('HGET',KEYS[1],'admissionAt'))
  local untilTime=exactNumber(redis.call('HGET',KEYS[1],'continuityExpiresAt'))
  if type(sha)=='string' and #sha==40 and string.match(sha,'^[0-9a-f]+$')
   and at and untilTime and untilTime>at and untilTime-at<=300000 then return 9 end
 end
 return -1
end
if state=='APPLIED' then return {exactOutcome(),winner,state} end
-- Once SQL has issued APPLY, later callers may only observe. A restored PREPARED
-- receipt is not authority to reapply, even with the same nonce/incarnation.
if action=='OBSERVE' then return {-1,winner,state} end
if now>=mutateUntil then return -1 end
local prior=exactOutcome()
if prior==7 or prior==9 then
 redis.call('HSET',KEYS[2],'state','APPLIED')
 return {prior,winner,'APPLIED'}
end
if now>=mutateUntil then return -1 end
if target~='none' then
 if target~='hash' or redis.call('PTTL',KEYS[1])~=-1 or redis.call('HLEN',KEYS[1])~=3
  or redis.call('HGET',KEYS[1],'binding')~=binding or redis.call('HGET',KEYS[1],'digest')~=digest
  or redis.call('HGET',KEYS[1],'state')~='AUTHORIZED' then return -1 end
end
redis.call('HSET',KEYS[1],'binding',binding,'digest',digest,'state','FENCED_INDETERMINATE','fenceDispatchId',dispatch)
redis.call('HSET',KEYS[2],'state','APPLIED')
return {5,winner,'APPLIED'}
