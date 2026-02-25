# FireMUD Player Experience Incident Runbook

This runbook describes operator actions for **player-facing SLO breaches** on login, command latency, and chat delivery. It complements the Player Experience SLIs/SLOs in `system-architecture-logging-monitoring.md` and the alert rules in `design/observability/grafana/core-alerts-snippets.md`.

## Incident Types

- **Login success ratio below SLO**
- **Command end-to-end latency above SLO**
- **Chat delivery latency above SLO**
- **Telnet and WebSocket path availability below SLO**

Use Grafana/Kibana/Jaeger when available. If any observability backend is degraded, follow the fallback procedures in `system-architecture-observability-incident-runbook.md` and the degraded-mode branches in each scenario below.

## Trace Preconditions (For Latency/Tick Root Cause)

Trace-driven triage is optional but often decisive for command-latency incidents. Before relying on Jaeger as a primary diagnostic:

- Confirm baseline tracing is usable for the affected path (production-like default is non-zero sampling; around 1% for high-volume entry paths is the baseline usability target from `system-architecture-tracing.md`).
- If traces are too sparse:
  - First escalate service-scoped sampling temporarily (`OTEL_TRACES_SAMPLER=parentbased_traceidratio`, increase `OTEL_TRACES_SAMPLER_ARG`) and record start/end times in the incident timeline.
  - If the environment supports collector tail-sampling by `tenantId`/`regionId`, prefer scoped escalation for the impacted tenant/region and remove the policy immediately after triage.
- If the environment does not meet the collector capability contract for tenant/region-scoped sampling, treat it as service-scoped-only and do not claim scoped escalation.
- If trace volume remains insufficient, continue with metrics + logs and do not block mitigation on trace availability.

## Login Success Ratio Below SLO

### Detect (Login success ratio)

- Alert: `LoginSuccessRatioLowGateway` or `LoginSuccessRatioLowTcpProxy` fires (for example, success ratio < 99.5% over 15 minutes).
- Player reports: widespread login failures or timeouts.
- Metrics:
  - Player Experience dashboard shows a drop in the login success panel.
  - Gateway/TCP Proxy logs show spikes in 4xx/5xx on login routes or connection refusals.

### Decide (Login success ratio)

- Determine scope:
  - Single tenant vs all tenants.
  - Single ingress path (Telnet vs WebSocket/HTTPS) or multiple.
- Decide if the incident is primarily:
  - **Edge-related** (TCP Proxy/Gateway/Cloud LB).
  - **Auth-related** (Account Service, JWT, database).
  - **Downstream capacity-related** (Game Session, Redis, Postgres).

### Act (Login success ratio)

1. **Check entry paths**
   - Compare Telnet vs WebSocket/HTTPS behavior:
     - If only Telnet is affected, follow the Telnet degraded runbook (`system-architecture-telnet-degraded-runbook.md`) and TCP Proxy dashboards.
     - If both are affected, continue below.
2. **Inspect Gateway and Account Service**
   - Use service-specific dashboards/logs to check:
     - Error rate and latency on login routes.
     - Dependency errors (database, Redis, external auth/email providers where applicable).
3. **Check backing services**
   - Confirm Postgres and Redis health via:
     - Database and Redis dashboards.
     - Redis tail-loss and coordination metrics (`redis_coordination_tail_loss_ms`, tick health).
4. **Mitigate**
   - For edge/Gateway issues:
     - Roll back problematic gateway config or deployment if a recent change coincides with the incident.
     - Temporarily scale Gateway replicas if CPU or memory saturation is observed.
   - For Account Service or database issues:
     - Scale the Account Service and database resources where safe.
     - If a recent migration or deployment is suspected, consider rollback and run smoke tests.
5. **Verify recovery**
   - Confirm the login success SLI panel returns to acceptable levels.
   - Ensure `LoginSuccessRatioLowGateway` and/or `LoginSuccessRatioLowTcpProxy` clear (as applicable) and player reports subside.
   - Use the `player-incident-drilldown.json` Kibana saved search to spot-check representative player logs by `playerId`, `tenantId`, and `traceId` to confirm that errors have returned to normal levels.
6. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: query Prometheus directly for login success ratio by ingress path and tenant.
   - If Kibana is down: use service logs from Gateway/TCP Proxy/Account pods filtered by `tenantId` and `correlationId`.
   - If Prometheus is down: prioritize service health endpoints and dependency health (Postgres/Redis), and use conservative ingress mitigation (rollback/scale) based on authoritative service signals.

## Command Latency Above SLO

### Detect (Command latency)

- Alert: `CommandLatencyP99HighGateway` or `CommandLatencyP99HighTcpProxy` fires (p99 command latency > 250ms over 5 minutes).
- Player reports: perceived lag or delayed command responses in game.
- Metrics:
  - Player Experience dashboard shows elevated command p99 latency.
  - Tick Health & Ledger dashboard shows whether tick execution or queue depth is also degraded.

### Decide (Command latency)

- Determine whether the latency is:
  - **Network/edge-bound** (Gateway/TCP Proxy queues or backpressure).
  - **Tick-bound** (tick execution p99 approaching `tick_lock_ttl_ms`).
  - **Downstream service-bound** (e.g., Entity Management, World Management, chat or automation calls from ticks).

### Act (Command latency)

1. **Check tick health first**
   - Use the Tick Health & Ledger dashboard:
     - Inspect `tick_execution_time_ms_p99 / tick_lock_ttl_ms` for affected regions.
     - Inspect `tick_retry_queue_depth` and `tick_command_queue_depth`.
   - If tick execution is also degraded:
     - Follow the scaling runbook (`system-architecture-scaling-runbook.md`) to adjust Game Session region density or add replicas before touching tick cadence.
2. **Check Redis coordination**
   - On the Redis & Coordination Health dashboard:
     - Inspect `redis_coordination_tail_loss_ms`.
     - Inspect coordination memory/key counts for anomalies.
   - If tail-loss SLOs are being breached, consult the Redis incident runbook (`system-architecture-redis-incident-runbook.md`).
3. **Inspect downstream domains**
   - For commands dominating latency:
     - Use Jaeger and service-specific dashboards to identify slow spans (e.g., `entity_apply_damage`, `room_resolve_look`).
     - Verify database query performance and indexes for those paths.
4. **Mitigate**
   - Scale the Game Session Service and/or hot downstream services where indicated.
   - If a recent release introduced expensive command logic, consider rollback or feature-flagging the new behavior.
5. **Verify recovery**
   - Ensure command p99 latency returns under the SLO threshold across core commands.
   - Confirm tick health metrics return to normal envelopes.
   - Use the `player-incident-drilldown.json` and `tick-region-logs.json` Kibana saved searches to correlate any remaining slow commands with specific `tenantId`/`regionId` and to verify that logs no longer show systemic timeouts or retries for hot commands.
6. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: run direct PromQL checks for command p99 latency, tick safety ratio, Redis tail-loss, and queue depth per affected tenant/region.
   - If Jaeger is down or sampling is insufficient: skip span-based narrowing and classify bottlenecks from metrics + structured logs only.
   - If Kibana is down: inspect Game Session and hot domain-service logs directly for timeout/retry spikes by `tenantId`/`regionId`.

## Chat Delivery Latency Above SLO

### Detect (Chat delivery latency)

- Alert: `ChatDeliveryLatencyP99High` fires (p99 chat delivery > 1s over 5 minutes).
- Player reports: delayed or missing chat messages.
- Metrics:
  - Player Experience dashboard shows elevated chat p99 latency.
  - Chat/social service dashboards show increased queue lengths or processing times.

### Decide (Chat delivery latency)

- Determine whether latency is:
  - **Ingress-bound** (Gateway/edge issues affecting chat commands).
  - **Chat service-bound** (processing pipelines, filter/moderation hooks, database/Redis calls).
  - **Downstream or cross-region-bound** (if chat relies on tick or region routing).

### Act (Chat delivery latency)

1. **Inspect chat service metrics**
   - Check:
     - Per-channel `chat_delivery_latency_ms_bucket` histograms.
     - Any internal queue depth metrics or backpressure indicators.
   - Determine if one channel type (e.g., global vs zone vs party) is affected more than others.
2. **Check dependencies**
   - Verify:
     - Chat-related Redis/cache health and tail-loss where relevant.
     - Database performance for chat message persistence or history retrieval.
3. **Mitigate**
   - Scale the chat/social service and dependencies as indicated by CPU/memory/queue depth.
   - If a new moderation/filtering feature was rolled out, consider temporarily disabling or throttling it.
4. **Verify recovery**
   - Confirm chat p99 latency returns below the SLO for active channels.
   - Ensure the alert clears and player reports improve.
   - Use the `player-incident-drilldown.json` Kibana saved search to validate that chat-related errors or delays in logs have subsided for affected players and channels.
5. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: query Prometheus directly for `chat_delivery_latency_ms_bucket` p99 by `tenantId`/`channel_type`.
   - If Kibana is down: inspect Social/Groups service logs directly using `tenantId` and correlation identifiers.
   - If Prometheus is down: use service health + queue/dependency indicators from application logs and reduce chat feature pressure (throttle or temporary feature disable) if needed.

## Telnet and WebSocket Path Availability Below SLO

### Detect (Entry path availability)

- Player reports: failed or flaky connections on one entry path (Telnet or WebSocket/HTTPS).
- Metrics:
  - Player Experience dashboard shows a drop in availability computed from `entrypath_connection_attempts_total{path,outcome}` for one or more tenants.
  - TCP Proxy dashboards show whether `tcpproxy_connections_limit_exceeded` or `tcpproxy_telnet_discarded` are elevated (Telnet path), and Gateway dashboards show whether WebSocket upgrade failures are elevated (WebSocket path).

### Decide (Entry path availability)

- Determine scope:
  - Single tenant vs all tenants (group by `tenantId`).
  - Single `path` vs multiple (`path="telnet"` vs `path="websocket"`).
- Determine dominant failure outcomes by inspecting `entrypath_connection_attempts_total` broken down by `outcome`:
  - `limit_exceeded` suggests caps or abusive clients.
  - `protocol_error` suggests client/edge parsing problems.
  - `upstream_unreachable` suggests Gateway or downstream availability issues.
  - `auth_failed` suggests account/JWT or session binding problems.

### Act (Entry path availability)

1. **Classify by path**
   - If `path="telnet"` only:
     - Follow the Telnet degraded runbook (`system-architecture-telnet-degraded-runbook.md`) and TCP Proxy dashboards/logs.
   - If `path="websocket"` only:
     - Inspect Gateway WebSocket upgrade metrics/logs and compare to general HTTP health.
   - If both paths are affected:
     - Treat as a broader edge/Gateway or downstream capacity incident; cross-check login SLI, Redis tail-loss, and tick health.
2. **Mitigate**
   - For cap-driven failures (`outcome="limit_exceeded"`):
     - Adjust caps (`TCP_PROXY_MAX_CONNECTIONS`, `TCP_PROXY_MAX_CONNECTIONS_PER_IP`) only if dashboards indicate normal load is being rejected rather than abusive traffic.
     - Consider rate-limiting or blocking abusive sources using documented edge controls.
   - For upstream failures (`outcome="upstream_unreachable"` or timeouts):
     - Scale or roll back Gateway/TCP Proxy if a recent change correlates with the incident.
     - Validate downstream dependencies (Redis/Postgres) and tick health for player-facing regions.
3. **Verify recovery**
   - Confirm availability returns above SLO for affected `{tenantId,path}` combinations and the dominant failure outcomes subside.
4. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: query Prometheus directly for `entrypath_connection_attempts_total` success/total ratios by `{tenantId,path}` and dominant `outcome`.
   - If Kibana is down: use Gateway/TCP Proxy logs directly to classify failures (`limit_exceeded`, `protocol_error`, `upstream_unreachable`, `auth_failed`).
   - If Prometheus is down: rely on edge health, pod events, and direct ingress error logs to guide rollback/scale/cap actions.
