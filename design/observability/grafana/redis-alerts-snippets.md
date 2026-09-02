# Redis Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for Redis coordination alerts. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Implementation status

The raw `redis_coordination_tail_loss_ms{scope}` series, when supplied by a deployment, is a bounded compatibility diagnostic only and is not measured-SLO authority. The Lua-script metrics and cadence-derived compatibility metrics used by the examples below are target-only and currently have no repository producer or owner contract. They are not installed by the shared PrometheusRule. A deployment may install these examples only after implementing and proving their bounded labels and owner contract.

## Redis Tail-Loss and Coordination Health

Example alert for Coordination Redis tail-loss SLO breaches:

```yaml
- alert: RedisCoordinationTailLossSLOBreached
  expr: redis_coordination_tail_loss_slo_breached{scope=~".+"} > 0
  for: 5m
  labels:
    service: redis-coordination
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-aof-tail-loss-slo-breach
  annotations:
    summary: Coordination Redis tail-loss SLO breached
    description: Tail-loss exceeds the cadence-derived envelope for one or more bounded Redis deployment scopes; identify the affected deployment scope(s) from the alert labels, then enumerate affected regions from authoritative runtime-health and control-plane records. See the Redis incident runbook for reset guidance.
```

This assumes that the canonical recording rules expose:

- `redis_coordination_tail_loss_budget_ms{scope}` – one bounded scalar per Coordination Redis deployment/environment/ruleset scope equal to the maximum `clamp_min(2 * tick_interval_ms, 2000)` across that scope's `tick_interval_ms{scope,scope_class}` series.
- `redis_coordination_tail_loss_slo_breached{scope}` – derived breach indicator joining each bounded exposure scope with its matching scoped budget on `scope`; `scope` and `scope_class` are separate labels and are not aliased.

Example alerts for additional Coordination Redis core red lines from the Redis metrics contract:

```yaml
- alert: RedisLuaScriptLoadFailures
  expr: increase(redis_lua_script_load_failures_total[5m]) >= 1
  for: 5m
  labels:
    service: redis-coordination
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Redis Lua script load failures detected
    description: One or more coordination shards are failing to load required Lua scripts.

- alert: RedisLuaScriptMissingForRegion
  expr: redis_lua_script_missing_for_region_total > 0
  for: 1m
  labels:
    service: redis-coordination
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Redis Lua script missing for active region
    description: Tick scheduling should halt for affected regions until script preload is healthy.

- alert: RedisLuaScriptRuntimeHigh
  expr: redis_lua_script_runtime_ms_p99{scope=~".+"} > on (scope) group_left() (2 * max by (scope) (tick_interval_ms{scope=~".+"}))
  for: 3m
  labels:
    service: redis-coordination
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Redis Lua script runtime exceeds budget
    description: Coordination script latency is beyond the runtime envelope and can degrade tick health.

- alert: RedisCoordinationOomErrors
  expr: increase(redis_coordination_oom_errors_total[1m]) >= 1
  for: 1m
  labels:
    service: redis-coordination
    severity: P0
    owner: infra
    runbook: design/architecture/system-architecture-redis-incident-runbook.md#coordination-redis-outage-or-degradation
  annotations:
    summary: Coordination Redis OOM errors detected
    description: Coordination writes are failing due to memory pressure; halt affected ticks until headroom is restored.

- alert: RedisTickPendingStuck
  expr: redis_tick_pending_stuck_total > 0
  for: 2m
  labels:
    service: redis-coordination
    severity: P1
    owner: infra
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Stuck tick pending state detected
    description: One or more regions have pending entries that are not converging and may need replay/reset action.
```
