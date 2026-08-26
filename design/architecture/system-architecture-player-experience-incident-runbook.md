# FireMUD Player Experience Incident Runbook

This runbook describes operator actions for **player-facing reliability signals and profile-promoted SLO breaches** on login, command latency, and chat delivery. It complements the Player Experience SLIs/SLOs in [Logging & Monitoring](./system-architecture-logging-monitoring.md) and the alert rules in [Player Experience alert snippets](../observability/grafana/player-experience-alerts-snippets.md).

Validation and runtime-proof selection for changes to this runbook follows the shared [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md) workflow. Record execution results in PR/CI evidence or the owning implementation tracker; this normative runbook is not a validation ledger.

Independent-monitoring applicability follows [ADR 0159](./decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md). Hosted profiles that claim externally verified availability or monitoring-resilient readiness must use an off-cluster monitor for every path in the profile's complete `exposedPublicPlayerPaths` set plus the in-cluster deadman signal; non-exposed paths are `not_applicable`. For an `independent-required` profile, a current authoritative external synthetic-probe result is required for each exposed path regardless of Prometheus availability. Treat a missing, stale, unavailable, or invalid result as `unknown`/degraded rather than green or failed; determine freshness from the retained observed age and the profile's declared detection budget. Hobby, single-node, and other small or otherwise non-required profiles may omit that monitor; when omitted, the preflight and incident record must state the degraded-detection, operator-dependent posture, and the runbook must use the strongest available local checks without implying external detection. Omission alone does not block player traffic.

## Implementation Status

The checked-in player-experience smoke harness and retained-evidence validator are implemented and contract-proven at their current operator-run boundary, but they are not continuously scheduled and no deployment-owned expected-series inventory is published. The deployment-owned `PlayerFlowCanaryEvidenceMissing` expected-series inventory and alert remain target-only and are not implemented. The authoritative off-cluster deadman/public-path monitor and pager remain environment-specific and are not shipped or proved by this repository. Accordingly, `independent-required` profiles need current retained external evidence for readiness or recovery; missing, stale, unavailable, or invalid evidence is `unknown`/degraded. `independent-omitted` profiles use local or operator-dependent checks and must not claim independent detection. Numeric thresholds in this runbook are calibration/template values unless a profile has promoted them using representative evidence, minimum-sample handling, and multi-window burn policy as required by [ADR 0160](./decisions/adr-0160-staged-profile-aware-player-experience-slo-contract.md).

## Incident Types

- **Login success ratio below SLO**
- **Command end-to-end latency above SLO**
- **Chat delivery latency above SLO**
- **Telnet and WebSocket path availability below SLO**

Use only the selected profile's advertised observability paths. The default indexed profile uses Grafana/Kibana/Jaeger, a compatible profile uses its documented mappings, and a reduced profile uses only its declared console/journal or direct service/pod paths without claiming indexed search. If any advertised backend is degraded, follow the degraded-observability procedures in `system-architecture-observability-incident-runbook.md` and the degraded-mode branches in each scenario below.

Synthetic canary identities used in this runbook are operational probes, not ordinary players. They remain subject to authentication, abuse controls, moderation, security monitoring, and durable audit. Validated canary traffic is excluded only from product analytics, ordinary player-behavior interpretation, and live-player SLO denominators.

Metrics in this runbook use the bounded `scope` contract from [Logging & Monitoring](./system-architecture-logging-monitoring.md#canonical-bounded-metrics-scope): pre-gameplay flows use `scope="environment"`, while each gameplay metric family documents any narrower bounded operational buckets it supports. Resolve an exact `<tenantId, gameInstanceId, regionId>` runtime scope through Game Session/control-plane runtime-health reads and structured logs before taking gameplay-scope action; do not infer exact runtime ownership from ordinary metric labels.

Log searches in this runbook must preserve the emitting `service` and `traceId` as the primary correlation fields, with `correlationId` when available. Add `tenantId`, `gameInstanceId`, `regionId`, and `characterId` only when those gameplay fields are present and expected by the affected record's logging contract; pre-gameplay records must not be forced to carry them.

## Direct External-Monitor Retrieval When Prometheus Is Unavailable

For an `independent-required` profile, retrieve the authoritative external-monitor result directly from the off-cluster monitor's native API or console, or from its retained evidence store outside the monitored cluster and Prometheus failure domain. Do not substitute a Prometheus mirror, and do not treat optional player-flow canary metrics as externally available through this path. The external deadman/public-path monitor remains the authority; see the [External Monitoring Contract](../observability/external-monitoring/README.md) for its owner-defined evidence shape.

Before using a retrieved result to classify an exposed public path, validate the following at the same trusted evaluation time:

- the declared profile is `independent-required` and its `exposedPublicPlayerPaths` set is complete; every exposed path has a corresponding public-path result, while non-exposed paths are `not_applicable`
- `deadmanAuthority` has a valid status and retains its required `target`, `evidenceRef`, and `checkRef`; `pageEvidenceRef` is required for green/readiness deadman evidence and may be absent for valid red incident evidence when no page-delivery observation exists. Every exposed public-path result has a valid status plus `target` and `evidenceRef`; `pageEvidenceRef` is required only when that path is green, and `checkRef` is not required on public-path results. A current valid red deadman or public-path result is outage evidence rather than `unknown` merely because page delivery is absent; missing or malformed required fields are not a failure result.
- At the trusted evaluation time, `evidenceObservedAt` is a valid UTC timestamp at or before that evaluation time. Compute its outer age as trusted evaluation time minus `evidenceObservedAt`, require that age to be non-negative, and only then compare it with the profile's configured `detectionBudgetSeconds`; future-dated evidence is `unknown`/degraded and cannot authorize traffic reopen or recovery. Every source observation timestamp is at or before `evidenceObservedAt`, `observedStalenessSeconds` equals `evidenceObservedAt - lastSuccessfulHeartbeatObservedAt`, and each exposed path's `observedProbeAgeSeconds` equals `evidenceObservedAt - lastSuccessfulProbeObservedAt`, all within the established numeric tolerance. A green deadman requires observed staleness no greater than `staleThresholdSeconds`, and a green exposed path requires observed probe age no greater than `detectionBudgetSeconds`.
- `evidenceRef`, `pageEvidenceRef`, `checkRef`, and target values are retained as opaque provider-owned references; do not infer freshness or status by parsing their names or timestamps

If any required profile, path, status, timestamp, age, or reference is missing, stale, unavailable, or invalid, classify the affected external-monitor evidence as `unknown`/degraded. Do not classify that path as green or failed, and do not use incomplete external evidence as traffic-reopen or recovery authority. Profiles with independent monitoring omitted use the strongest available local/public-edge checks and retain their documented operator-dependent degraded posture.

## Trace Preconditions (For Latency/Tick Root Cause)

Trace-driven triage is optional but often decisive for command-latency incidents. Before relying on Jaeger as a primary diagnostic:

- Confirm baseline tracing is usable for the affected path (production-like default is non-zero sampling; around 1% for high-volume entry paths is the baseline usability target from `system-architecture-tracing.md`).
- If traces are too sparse:
  - Use temporary service-scoped sampling (`OTEL_TRACES_SAMPLER=parentbased_traceidratio`, increase `OTEL_TRACES_SAMPLER_ARG`) only when the environment advertises and independently proves ADR 0017 level 3 for the affected workflow; record start/end times in the incident timeline.
  - Use tenant/game-instance/region-scoped escalation only when the environment advertises and independently proves ADR 0017 level 4 for the affected workflow, and only after the exact runtime scope is resolved through control-plane/runtime-health reads and logs. Remove the policy immediately after triage.
- If the environment does not meet the advertised-and-proved ADR 0017 capability for the required level, treat it as baseline or service-scoped-only as applicable and do not claim scoped escalation.
- If trace volume remains insufficient, continue with metrics + logs and do not block mitigation on trace availability.

## Login Success Ratio Below SLO

### Detect (Login success ratio)

- Alert: `LoginSuccessRatioLowGateway` or `LoginSuccessRatioLowTcpProxy` fires for a profile that has explicitly promoted this exact objective (for example, success ratio < 99.5% over 15 minutes) using representative evidence, minimum-sample handling, and multi-window policy. Without that exact promotion, treat the threshold as calibration/template evidence, not a universal current SLO breach.
- Where the profile advertises the player-flow canary capability, and only for paths in its complete `exposedPublicPlayerPaths` set, `playerflow_canary_success{flow="login",path=...,target=...,profile=...}` alerts may fire before live-traffic SLIs move materially in low-traffic environments. Use the matching `playerflow_canary_last_run_timestamp_seconds{flow="login",path=...,target=...,profile=...}` and profile-derived freshness budget before treating the result as actionable. An omitted capability or non-exposed path is `not_applicable`; missing, stale, or unavailable advertised evidence is `unknown`/degraded rather than a canary failure.
- `PlayerFlowCanaryEvidenceStale` means the available canary evidence exceeded the matching profile budget, or a present `playerflow_canary_success` result has no matching last-run timestamp; treat player-flow health as unknown/degraded until a fresh run is retained, rather than as a synthetic login or command failure. A wholly absent expected flow/path/target/profile tuple belongs to `PlayerFlowCanaryEvidenceMissing` once its deployment-owned expected-series inventory exists.
- Player reports: widespread login failures or timeouts.
- Metrics:
  - Player Experience dashboard shows a drop in the login success panel.
  - Gateway/TCP Proxy logs show spikes in 4xx/5xx on login routes or connection refusals.

### Decide (Login success ratio)

- Determine scope:
  - Use metrics only to classify deployment-wide degradation versus an approved bounded `scope` bucket.
  - When the affected record carries gameplay identity, resolve the exact tenant/game-instance/region and ingress path through control-plane/runtime-health reads and structured logs. For pre-gameplay login failures without gameplay identity, classify the deployment-wide environment, ingress path, and synthetic probe target instead; do not infer a game instance or region.
- Decide if the incident is primarily:
  - **Edge-related** (TCP Proxy/Gateway/Cloud LB).
  - **Auth-related** (Account Service, JWT, database).
  - **Downstream capacity-related** (Game Session, Redis, Postgres).

### Act (Login success ratio)

1. **Check entry paths**
   - Compare Telnet vs WebSocket/HTTPS behavior:
     - If only Telnet is affected, follow the Telnet degraded runbook (`system-architecture-telnet-degraded-runbook.md`) and TCP Proxy dashboards.
     - If both are affected, continue below.
   - When a player-flow canary is configured for the profile, use it as an investigation trigger only when current, fresh, valid evidence exists for an exposed path. If that canary is failing while live login-volume SLIs are flat, corroborate it with live-traffic and authoritative service signals and rule out canary identity or test-data failure before classifying a public outage or applying mitigation. If evidence is missing, stale, unavailable, `not_applicable`, or otherwise invalid, use live-traffic and authoritative service signals instead.
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
   - Confirm the login success SLI panel returns to the profile-promoted objective when one exists; otherwise compare it with the provisional calibration baseline and keep the result informational.
   - Ensure `LoginSuccessRatioLowGateway` and/or `LoginSuccessRatioLowTcpProxy` clear (as applicable) and player reports subside.
   - Use the selected profile's supported operator-query path to spot-check representative logs by `service`, `traceId`, and `correlationId`, adding `tenantId` or `characterId` only when those fields are present, to confirm that errors have returned to normal levels. The default indexed profile uses the `player-incident-drilldown.json` Kibana saved search, a compatible indexed profile uses its mapped equivalent, and a reduced profile uses only its declared console/journal or direct service/pod path.
6. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: query Prometheus directly for `login_requests_total` success ratio by its available `scope`, `service`, and `outcome` labels, using the deployment-wide `scope="environment"` baseline. If the profile advertises player-flow canaries, use `playerflow_canary_success{flow="login",path=...,target=...,profile=...}` with its matching fresh last-run timestamp and profile budget to distinguish only exposed ingress paths; omitted capability or non-exposed paths are `not_applicable`, and missing, stale, or unavailable advertised evidence is `unknown`/degraded. `login_requests_total` itself has no `path` label. Do not require `gameInstanceId` or `regionId`: login occurs before gameplay scope is selected.
   - If the selected indexed query path is unavailable, or the profile omits indexed search: use the profile's declared console/journal path or direct service logs from Gateway/TCP Proxy/Account pods filtered by `service`, `traceId`, and `correlationId`; do not require gameplay identity fields for this pre-gameplay login path.
   - If Prometheus is down: for an `independent-required` profile, first follow [Direct External-Monitor Retrieval When Prometheus Is Unavailable](#direct-external-monitor-retrieval-when-prometheus-is-unavailable); classify missing, stale, unavailable, or invalid external evidence as `unknown`/degraded, then use service health endpoints and dependency health (Postgres/Redis) as supplementary login classification and action evidence. Profiles with independent monitoring omitted use those strongest available local signals and retain their operator-dependent degraded posture. Apply conservative ingress mitigation (rollback/scale) based on authoritative service signals.

## Command Latency Above SLO

### Detect (Command latency)

- Alert: `CommandLatencyP99HighGateway` or `CommandLatencyP99HighTcpProxy` fires for a profile that has explicitly promoted this exact objective (for example, p99 command latency > 250ms over 5 minutes) using representative evidence, minimum-sample handling, and multi-window policy. Without that exact promotion, treat the threshold as calibration/template evidence, not a universal current SLO breach.
- Where the profile advertises the player-flow canary capability, and only for paths in its complete `exposedPublicPlayerPaths` set, `playerflow_canary_success{flow="command",path=...,target=...,profile=...}` or `playerflow_canary_latency_ms{flow="command",path=...,target=...,profile=...}` alerts may fire before traffic-derived latency panels move in low-volume periods. Use the matching `playerflow_canary_last_run_timestamp_seconds{flow="command",path=...,target=...,profile=...}` and profile-derived freshness budget before treating success or latency as actionable. An omitted capability or non-exposed path is `not_applicable`; missing, stale, or unavailable advertised evidence is `unknown`/degraded.
- Player reports: perceived lag or delayed command responses in game.
- Metrics:
  - Player Experience dashboard shows elevated command p99 latency for one or more bounded core commands (`move`, `look`, `combat`).
  - Tick Health & Ledger dashboard shows whether tick execution or queue depth is also degraded.

### Decide (Command latency)

- Determine whether the latency is:
  - **Network/edge-bound** (Gateway/TCP Proxy queues or backpressure).
  - **Tick-bound** (tick execution p99 approaching `tick_lock_ttl_ms`).
  - **Downstream service-bound** (e.g., Entity Management, World Management, chat or automation calls from ticks).

### Act (Command latency)

1. **Check tick health first**
   - Use the Tick Health & Ledger dashboard:
     - Inspect `tick_execution_time_ms_p99 / tick_lock_ttl_ms` for affected approved scope buckets, then resolve exact regions through control-plane/runtime-health reads and structured logs.
     - Inspect `tick_retry_queue_depth` and `tick_command_queue_depth`.
   - If the representative command canary is advertised for the exposed path and has current, fresh, valid evidence showing failure or slowness while the live-traffic SLI is quiet, use that result as the trigger to continue triage rather than waiting for more user traffic. Otherwise treat the canary as `not_applicable` or `unknown`/degraded and use live-traffic and authoritative service signals.
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
     - Start with the per-command latency series from `command_latency_ms_p99_gateway_5m` / `command_latency_ms_p99_tcpproxy_5m`; do not rely on an aggregate “all core commands” rollup as the primary decision signal.
     - Use stage-split command latency metrics (`command_latency_stage_ms_bucket`) first to determine whether the regression is in `edge_queue`, `dispatch`, `tick_wait`, or `domain_commit` before relying on trace sampling.
     - Verify database query performance and indexes for those paths.
4. **Mitigate**
   - Scale the Game Session Service and/or hot downstream services where indicated.
   - If a recent release introduced expensive command logic, consider rollback or feature-flagging the new behavior.
5. **Verify recovery**
   - Ensure command p99 latency returns under the profile-promoted objective when one exists; otherwise compare it with the provisional calibration baseline and keep the result informational.
   - Confirm tick health metrics return to normal envelopes.
   - Use the selected profile's supported operator-query path to correlate any remaining slow commands by `service` and `traceId`, adding `correlationId` and the applicable `tenantId`, `gameInstanceId`, `regionId`, `characterId`, and `tickId` fields only when the affected records expose them. The default indexed profile uses the `player-incident-drilldown.json` and `tick-region-logs.json` Kibana saved searches, a compatible indexed profile uses its mapped equivalents, and a reduced profile uses only its declared console/journal or direct service/pod path. Resolve the exact `<tenantId, gameInstanceId, regionId>` runtime scope through Game Session/control-plane runtime-health reads and structured logs rather than inferring `gameInstanceId` from query filters.
6. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: run direct PromQL checks for command p99 latency, synthetic command-canary success/latency only when current, fresh, valid advertised evidence exists for the matching profile and exposed path, tick safety ratio, Redis tail-loss, and queue depth per affected gameplay `scope`; otherwise use live-traffic and authoritative service signals.
   - If Jaeger is down or sampling is insufficient: skip span-based narrowing and classify bottlenecks from metrics + structured logs only.
   - If the selected indexed query path is unavailable, or the profile omits indexed search: use the profile's declared console/journal path or inspect Game Session and hot domain-service logs directly by `service` and `traceId`, adding `correlationId` and conditional gameplay identity fields (`tenantId`, `gameInstanceId`, `regionId`, `characterId`) only when present in the affected records.

## Chat Delivery Latency Above SLO

### Detect (Chat delivery latency)

- Alert: `ChatDeliveryLatencyP99High` fires for a profile that has explicitly promoted this exact objective (for example, p99 chat delivery > 1s over 5 minutes) using representative evidence, minimum-sample handling, and multi-window policy. Without that exact promotion, treat the threshold as calibration/template evidence, not a universal current SLO breach.
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
     - Per-service and per-channel `chat_delivery_latency_ms_bucket{service,scope,completion_boundary="recipient_dispatch",channel_type,le}` histograms. The canonical player-experience chat SLI selects only `completion_boundary="recipient_dispatch"`; other boundary values are diagnostic and must not be substituted, combined, or aggregated into this SLI.
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
   - Confirm chat p99 latency returns below the profile-promoted objective when one exists; otherwise compare it with the provisional calibration baseline and keep the result informational.
   - Ensure the alert clears and player reports improve.
   - Use the selected profile's supported operator-query path to validate that chat-related errors or delays have subsided by `service` and `traceId`, adding `correlationId` and conditional gameplay identity fields only when present in the affected records. The default indexed profile uses the `player-incident-drilldown.json` Kibana saved search, a compatible indexed profile uses its mapped equivalent, and a reduced profile uses only its declared console/journal or direct service/pod path.
5. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down: query Prometheus directly for `chat_delivery_latency_ms_bucket{completion_boundary="recipient_dispatch"}` p99 by `service` / `scope` / `channel_type`. Do not substitute, combine, or aggregate other completion-boundary values into the canonical player-experience chat SLI.
   - If the selected indexed query path is unavailable, or the profile omits indexed search: use the profile's declared console/journal path or inspect Social/Groups service logs directly using `service` and `traceId`, adding `correlationId` and conditional `tenantId`/`gameInstanceId`/`regionId`/`characterId` fields when present.
   - If Prometheus is down: use service health + queue/dependency indicators from application logs and reduce chat feature pressure (throttle or temporary feature disable) if needed.

## Telnet and WebSocket Path Availability Below SLO

### Detect (Entry path availability)

- Player reports: failed or flaky connections on one entry path (Telnet or WebSocket/HTTPS).
- Metrics:
  - Player Experience dashboard shows a drop in availability computed from `entrypath_connection_attempts_total{service,scope,path,outcome}` for one or more approved bounded `service` and `scope` buckets. Resolve an exact tenant/game-instance/region through control-plane/runtime-health reads and structured logs only when the affected connection record has gameplay identity; otherwise use the emitting service, environment, entry `path`, and external/synthetic `probe target` as the operational scope.
  - For profiles requiring independent monitoring, external synthetic probes show whether the public Telnet or WebSocket path completes its protocol handshake when traffic may not be reaching Gateway or TCP Proxy. Omitted profiles use the strongest available local edge/public-path check and retain the degraded-detection posture.
  - TCP Proxy dashboards show whether `tcpproxy_connections_limit_exceeded` or `tcpproxy_telnet_discarded` are elevated (Telnet path), and Gateway dashboards show whether WebSocket upgrade failures are elevated (WebSocket path).

### Decide (Entry path availability)

- Determine scope:
  - Single approved bounded `scope` bucket vs the deployment-wide baseline.
  - Single `path` vs multiple (`path="telnet"` vs `path="websocket"`).
- Determine dominant shared outcome classes by inspecting `entrypath_connection_attempts_total{service,scope,path,outcome}` broken down by `service` and canonical `outcome`. `server_failure` identifies platform-attributable availability failures; `user_rejection` and `policy_rejection` remain diagnostic but are excluded from the availability denominator; `unknown` requires conservative investigation. Use the documented bounded diagnostic reason, when available, or protected logs to identify the specific cause:
  - `policy_rejection` with a cap or abuse-control reason suggests caps or abusive clients.
  - `user_rejection` with a malformed-protocol or authentication reason suggests client/edge parsing or account/session binding problems.
  - `server_failure` with an upstream-unreachable or timeout reason suggests Gateway or downstream availability issues.
  - For profiles with independent monitoring, use each fresh authoritative external synthetic-probe result alongside local edge and control-plane/runtime-health evidence. A missing, stale, unavailable, or invalid external result is `unknown`/degraded rather than green or failed. When a current external probe is failing and `entrypath_connection_attempts_total` is flat or absent, treat the external result as authoritative evidence of off-cluster reachability failure and use local/control-plane evidence to distinguish ingress/LB/TLS/DNS failure from an application-level success-ratio problem.
  - For profiles that omit independent monitoring, use the strongest available local edge/public-path check and keep degraded detection visible. A stale or unknown local check cannot classify an outage alone; a current failing local check must be corroborated with control-plane/runtime-health reads or ingress logs before classifying an edge outage.

### Act (Entry path availability)

1. **Classify by path**
   - If `path="telnet"` only:
     - Follow the Telnet degraded runbook (`system-architecture-telnet-degraded-runbook.md`) and TCP Proxy dashboards/logs.
   - If `path="websocket"` only:
     - Inspect Gateway WebSocket upgrade metrics/logs and compare to general HTTP health.
   - If both paths are affected:
     - Treat as a broader edge/Gateway or downstream capacity incident; cross-check login SLI, Redis tail-loss, and tick health.
2. **Mitigate**
   - For cap- or abuse-driven `policy_rejection` identified by its bounded diagnostic reason or protected logs:
     - Adjust caps (`TCP_PROXY_MAX_CONNECTIONS`, `TCP_PROXY_MAX_CONNECTIONS_PER_IP`) only if dashboards indicate normal load is being rejected rather than abusive traffic.
     - Consider rate-limiting or blocking abusive sources using documented edge controls.
   - For `server_failure` identified by an upstream-unreachable or timeout diagnostic reason:
     - Scale or roll back Gateway/TCP Proxy if a recent change correlates with the incident.
     - Validate downstream dependencies (Redis/Postgres) and tick health for player-facing regions.
3. **Verify recovery**
   - Confirm the short-window detection view recovers quickly for every affected `{service,scope,path}` combination and the dominant failure outcomes subside. Use control-plane/runtime-health reads and structured logs for exact runtime scope only when gameplay identity is present; otherwise verify the environment, entry path, and probe target.
   - Confirm the 1-day compliance view trends back toward the profile-promoted objective when one exists; otherwise retain it as a calibration view after the acute incident is resolved.
4. **Degraded-mode branch (if observability backends are unavailable)**
   - If Grafana is down while Prometheus remains available: query Prometheus directly for `entrypath_connection_attempts_total` success/total ratios by `{service,scope,path}`. Use mirrored login/command canary metrics only when the profile advertises the player-flow canary and the corresponding path is exposed; an omitted capability or non-exposed path is `not_applicable`, while missing or stale evidence for a required canary is `unknown`/degraded rather than green or failed. For `independent-required` profiles, use a fresh authoritative external synthetic-probe result for every exposed public path alongside those Prometheus and local signals; missing, stale, unavailable, or invalid external evidence for any path is `unknown`/degraded rather than green or failed. Omitted or otherwise non-required profiles use the strongest local edge/public-path checks and retain degraded detection.
   - If the selected indexed query path is unavailable, or the profile omits indexed search: use the profile's declared console/journal path or Gateway/TCP Proxy logs directly, preserving `service`, `traceId`, and `correlationId` and adding conditional gameplay identity fields only when present, to classify canonical outcomes (`server_failure`, `user_rejection`, `policy_rejection`) and their bounded diagnostic reasons.
   - If Prometheus is down: follow [Direct External-Monitor Retrieval When Prometheus Is Unavailable](#direct-external-monitor-retrieval-when-prometheus-is-unavailable) for `independent-required` profiles, then use edge health, pod events, and direct ingress error logs as supplementary classification and action evidence. Omitted or otherwise non-required profiles use those strongest available local signals with the same stale/unknown handling and retain degraded detection.

### Gameplay close classification branch

For a player-reported disconnect, capture the bounded top-level close reason (`logout`, `session_replaced`, `service_restart`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable`) and any separate `subreason`. The top-level close reason drives lifecycle and retry handling; `subreason` is diagnostic context only and never a lifecycle class. `service_restart`, `session_replaced`, and `logout` are top-level reasons, not subreasons: `service_restart` is planned maintenance, `session_replaced` is takeover/displaced-controller, and `logout` is terminal. If a known `{tenantId, gameInstanceId, commandId}` exists, reconcile it through Game Session's authoritative command-status surface before deciding whether work committed; never infer command outcome from the close class. If no valid close frame or attribution exists, use the observation-specific fallback: an established authenticated TCP Proxy bridge surfaces `backend_unavailable`, while a WebSocket client treats the loss as abnormal transport loss and follows the `internal_error` retry policy. See the [Gateway close translation matrix](./system-architecture-gateway.md#canonical-close-translation-matrix) and [ADR 0131](./decisions/adr-0131-lifecycle-distinct-gameplay-close-taxonomy.md) for the normative contract.
