-- Forward-only transfer dispatch receipt prototype. Not registered or wired to production.
-- PREPARE never touches the execution key. Its immutable cutoff must elapse before SQL seals.
-- Only the exact sealed receipt may APPLY; APPLIED is read-only and cannot recreate a lost fence.
if #KEYS ~= 2 or #ARGV ~= 7 then return -2 end
local action, binding, digest, dispatch, incarnation = ARGV[1],ARGV[2],ARGV[3],ARGV[4],ARGV[5]
local prepareUntil, mutateUntil = tonumber(ARGV[6]),tonumber(ARGV[7])
if type(binding)~='string' or #binding==0 or #binding>524288
 or not prepareUntil or not mutateUntil or prepareUntil~=math.floor(prepareUntil)
 or mutateUntil~=math.floor(mutateUntil) or prepareUntil<=0 or mutateUntil<=0
 or mutateUntil>9007199254740991 then return -2 end
if #digest~=71 or string.sub(digest,1,7)~='sha256:' or not string.match(string.sub(digest,8),'^[0-9a-f]+$')
 or #incarnation~=71 or string.sub(incarnation,1,7)~='sha256:'
 or not string.match(string.sub(incarnation,8),'^[0-9a-f]+$')
 or #dispatch~=36 or not string.match(dispatch,'^[0-9a-f%-]+$') then return -2 end
local time=redis.call('TIME')
local seconds,micros=tonumber(time[1]),tonumber(time[2])
if not seconds or not micros or seconds<=0 or micros<0 or micros>=1000000
 or seconds>math.floor((9007199254740991-999)/1000) then return -1 end
local now=seconds*1000+math.floor(micros/1000)
local kind=redis.call('TYPE',KEYS[2]).ok
if kind=='none' then
 if action~='PREPARE' or now>=prepareUntil or prepareUntil-now>5000 then return -1 end
 redis.call('HSET',KEYS[2],'digest',digest,'dispatch',dispatch,'incarnation',incarnation,
  'prepareUntil',ARGV[6],'state','PREPARED')
 return 1
end
if kind~='hash' or redis.call('PTTL',KEYS[2])~=-1 or redis.call('HLEN',KEYS[2])~=5
 or redis.call('HGET',KEYS[2],'digest')~=digest or redis.call('HGET',KEYS[2],'dispatch')~=dispatch
 or redis.call('HGET',KEYS[2],'incarnation')~=incarnation
 or redis.call('HGET',KEYS[2],'prepareUntil')~=ARGV[6] then return -1 end
local state=redis.call('HGET',KEYS[2],'state')
if state~='PREPARED' and state~='APPLIED' then return -1 end
if action=='PREPARE' then
 if now>=prepareUntil then return -1 end
 return state=='PREPARED' and 1 or -1
end
if action=='OBSERVE' then
 if now<prepareUntil then return -1 end
 return state=='PREPARED' and 2 or 8
end
if action~='APPLY' or now<prepareUntil then return -1 end
local target=redis.call('TYPE',KEYS[1]).ok
if state=='APPLIED' then
 -- Lost/reset target after a successful application stays held. Never recreate it.
 if target~='hash' or redis.call('PTTL',KEYS[1])~=-1 or redis.call('HLEN',KEYS[1])~=4
  or redis.call('HGET',KEYS[1],'binding')~=binding or redis.call('HGET',KEYS[1],'digest')~=digest
  or redis.call('HGET',KEYS[1],'state')~='FENCED_INDETERMINATE'
  or redis.call('HGET',KEYS[1],'fenceDispatchId')~=dispatch then return -1 end
 return 8
end
if now>=mutateUntil then return -1 end
if target~='none' then
 if target~='hash' or redis.call('PTTL',KEYS[1])~=-1 or redis.call('HLEN',KEYS[1])~=3
  or redis.call('HGET',KEYS[1],'binding')~=binding or redis.call('HGET',KEYS[1],'digest')~=digest
  or redis.call('HGET',KEYS[1],'state')~='AUTHORIZED' then return -1 end
end
redis.call('HSET',KEYS[1],'binding',binding,'digest',digest,'state','FENCED_INDETERMINATE','fenceDispatchId',dispatch)
redis.call('HSET',KEYS[2],'state','APPLIED')
return 5
