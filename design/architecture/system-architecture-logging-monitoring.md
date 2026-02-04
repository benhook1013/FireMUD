# FireMUD Logging & Monitoring Overview

This document describes how FireMUD collects logs, metrics, and traces across all services, and how operators use those signals for debugging, moderation, and performance analysis.

---

## Logging Pipeline

- **Fluent Bit** sidecars collect service logs from every microservice.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries and embeds Kibana dashboards via its API for richer visualization.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`) and the active `playerId` for moderation context.
- Kibana dashboards filter by both `traceId` and `playerId` to narrow investigations quickly.
- gRPC services use the shared `LoggingInterceptor` to include `traceId` and `correlationId` in every log entry. See [Shared Libraries](./system-architecture-shared-libraries.md).
- Log retention defaults to **14 days** in development and **90 days** in production, after which indices are archived. These values can be tuned via the [Deployment Environments](./infrastructure/deployment-environments.md) settings.
- Log storage hosts can be customized via the `FLUENT_ELASTICSEARCH_HOST` and `FLUENT_ELASTICSEARCH_PORT` environment variables ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability)).
- The local Docker Compose stack includes Fluent Bit, Prometheus, Grafana, and Jaeger in addition to streaming logs to the console.
- Operators search logs primarily through Kibana. Sample Grafana and Kibana dashboards live under [`design/observability`](../observability) and are described in [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md). The Logging & Admin Service provides a dedicated UI for moderation and audit trails.

## Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- The Logging & Admin Service queries Prometheus for metrics and Jaeger for trace analysis to power moderation dashboards and investigations. See [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md) for examples.
- It calls the Grafana API to embed existing dashboards alongside Kibana views for a unified operator experience.
- The service consumes Alertmanager notifications so operators can triage alerts inside the admin UI.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests. Traces are collected by an OpenTelemetry Collector and visualized with Jaeger. See [Tracing](./system-architecture-tracing.md) for deployment details.
- Sample Kubernetes manifests under [`k8s/monitoring`](../../k8s/monitoring) deploy the collector and Jaeger (`otel-collector.yaml`, `jaeger.yaml`).
- Metrics are recorded with Micrometer. The shared `MetricsInterceptor` tracks `grpc.server.requests` for each call. Services increment the `grpc.app_error` counter in their `error()` helpers as described in the [gRPC API Style guidelines](./system-architecture-grpc.md).
- Business methods in services are annotated with `@Timed` to publish custom Prometheus timers.
- Most services expose a `/actuator/prometheus` endpoint for metrics. Scrape intervals are tuned per environment (typically 15s in development and 30s in production).
- Metrics for Redis are collected via the [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment, and a PostgreSQL exporter is available for database metrics. Redis dashboards surface Lua script latency, lock contention, retry queue depth, keyspace hits/misses, eviction rates, and latency percentiles for tick-related commands so operators can distinguish cache pressure from coordination issues. The minimum Redis SLOs and alert wiring are defined in the Tail-Loss observability and **Coordination Metrics & Thresholds Contract** sections of [Redis Operations & Migrations](./system-architecture-redis-operations.md#tail-loss-slo-observability), which summarize the core metrics and alerts that must be wired for each Redis role.
  - Additional application metrics track:
    - Failed lock acquisitions per region (for example `redis_tick_lock_acquire_failed`) to highlight contention hotspots.
    - The ratio of replayed ticks to total ticks (for example `gamesession_tick_replayed_total` vs `gamesession_tick_executed_total`) so operators can see when idempotent recovery paths are being exercised frequently.
    - Tick runtime safety margins per region, using `tick_execution_time_ms_bucket` and derived recording rules such as `tick_execution_time_ms_p95` / `tick_execution_time_ms_p99` alongside `tick_lock_ttl_ms` to compute safety ratios. Regions where `tick_execution_time_ms_p99 / tick_lock_ttl_ms` regularly exceeds a configured fraction of `tick_lock_ttl_ms` (for example `0.5×` for warning, `0.75×` for critical) are treated as **degraded** and surfaced in dashboards.
    - Lock refresh usage, via counters such as `redis_tick_lock_refresh_requests_total` and `redis_tick_lock_refresh_denied_total`, so operators can detect commands that routinely rely on the optional lock refresh helper instead of completing within the normal lock TTL.
- **Automation & Scripting metrics** surface scheduler, quota, and sandbox behavior:
  - Scheduler and budget meters such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `automation_script_queue_delay_seconds`, `automation_script_leadership_changes_total`, and `automation_script_tenant_budget_seconds{tenantId, tier}`.
  - Quota and tick integration meters such as `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total`.
  - Sandbox and runtime meters such as `automation_script_sandbox_failures_total{reason=...}`, `automation_script_errors_total{tenantId, reason=...}`, and `automation_script_runtime_seconds`.
  These metrics are described in more detail in [System Architecture: Scripting & Automation](./system-architecture-scripting.md) and the [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md).
- **TCP Proxy metrics** capture Telnet DMZ behavior:
  - Connection meters such as `tcpproxy.connections.active`, `tcpproxy.connections.total`, and `tcpproxy.connections.limit.exceeded` to surface global and per-IP caps derived from `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP`.
  - Safety and abuse meters such as `tcpproxy.telnet.discarded` (malformed Telnet negotiation, buffer overflows, repeated malformed `SESSION` envelopes, or invalid PROXY protocol headers) and `tcpproxy.tls.misconfig` (TLS startup errors). These allow operators to see when legacy Telnet clients, MCP tools, or misconfigurations are causing noisy connections at the DMZ boundary.
  - WebSocket bridge meters such as `tcpproxy.websocket.reconnects`, `tcpproxy.websocket.reconnect.delay`, and timers like `tcpproxy.command`, `tcpproxy.heartbeat`, and `tcpproxy.idleClose` to track how often the proxy must reconnect to Spring Cloud Gateway, how long backoff delays are, and where time is spent in the Telnet pipeline.
  - Lifecycle and integration meters such as `tcpproxy.disconnect.notify.failure` to surface best-effort `NotifyDisconnect` delivery problems to the Game Session Service; these complement Game Session’s own session-takeover and resume counters described in the Reconnection Strategy doc and the TCP Proxy design’s **Service Interactions** section.
  Operators should create Grafana panels and Alertmanager rules that highlight sustained non-zero `tcpproxy.connections.limit.exceeded`, sharp increases in `tcpproxy.telnet.discarded`, and recurring `tcpproxy.disconnect.notify.failure` spikes, since these typically indicate abusive clients, mis-tuned caps, TCP edge misconfiguration (for example bad PROXY headers), or issues on the Game Session side.
- Distributed traces are exported via OTLP and correlated with logs using a shared `traceId` in spans and log entries; metrics do **not** include `traceId` as a label. Correlation between metrics and traces should rely on exemplars where available and on log search and Jaeger queries rather than high-cardinality metric labels.
- The OpenTelemetry collector endpoint is configurable via the `OTEL_ENDPOINT` environment variable ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)).
- For the new data-driven `LOOK` path, see `../project-management/look-instrumentation.md` for the specific `gamesession.command.look.*` meters, log conventions, and tracing guidance that operators should monitor while the slice stabilizes.

### Cardinality Guardrails for Metrics

FireMUD’s metrics are designed around low- and medium-cardinality labels so dashboards and alerts remain reliable even at scale. To keep this consistent:

- Allowed labels typically include `service`, `job`, `grpc_method`, `status`, `code`, `tenantId`, `regionId`, `redis_role`, `effect_type`, `outcome`, and other explicitly documented dimensions in the Redis, tick, and gRPC architecture docs.
- Disallowed labels include per-request or per-entity identifiers such as `traceId`, `spanId`, `playerId`, `sessionId`, and arbitrary error messages or stack traces. These belong in logs and traces, not in metric label sets.
- When in doubt, prefer coarser labels (for example `error_code` from a bounded enum or small string set) and aggregate multiple rare values into an `other` bucket rather than exposing them as unbounded labels.
- New metrics must document their label sets in the relevant architecture or service README and confirm that they conform to these guardrails before being added to dashboards or alerts.

### Player Experience SLIs and SLOs

In addition to infrastructure-level SLOs for Redis, ticks, and backup pipelines, FireMUD tracks a small set of player-centric SLIs. These are expressed as Prometheus metrics with environment-specific SLO targets:

- **Login success ratio**
  - SLI: fraction of successful login attempts over total login attempts, for example `login_requests_total{outcome="success"}` vs `login_requests_total`.
  - SLO (production starting point): ≥ 99.5% success over a 15-minute rolling window, evaluated per `tenantId` and, where applicable, `regionId`.
  - Instrumentation: emitted by Spring Cloud Gateway (and any protocol-bridging entry points such as the TCP Proxy) for login-related routes, with labels at least `{tenantId, regionId, outcome}`.
- **Command end-to-end latency**
  - SLI: gateway-to-domain command latency, measured from reception at Gateway or TCP Proxy through to domain commit, for example `command_end_to_end_latency_ms` histogram with labels such as `command`, `tenantId`, `regionId`.
  - SLO: 99% of core gameplay commands (movement, look, combat) complete in < 250ms over a 5-minute window, per `tenantId`/`regionId`.
  - Instrumentation: emitted by Gateway (and optionally the TCP Proxy for Telnet) with labels `{command, tenantId, regionId}`. Core commands such as movement, LOOK, and combat should use a small, documented set of `command` label values so per-command latency panels remain low-cardinality.
- **Telnet/WebSocket path availability**
  - SLI: success rate and error rate for Telnet and WebSocket upgrade/connection flows, derived from metrics such as `tcpproxy.connections.active`, `tcpproxy.connections.limit.exceeded`, and HTTP/gRPC status codes on the WebSocket bridge.
  - SLO: ≥ 99.9% of connection attempts succeed over a 1-day window; sustained deviations are treated as P0 incidents for the affected entry path.
  - Instrumentation: primarily derived from TCP Proxy and Gateway meters (`tcpproxy_connections_total`, `tcpproxy_connections_limit_exceeded`, WebSocket upgrade status counters), with labels `{tenantId}` and, where safe, coarse-grained client metadata.
- **Chat delivery latency**
  - SLI: time from chat message submission to delivery to all intended recipients, for example `chat_delivery_latency_ms` histogram keyed by `tenantId` and chat channel type.
  - SLO: 99% of chat messages are delivered in < 1s over a 5-minute window for active regions.
  - Instrumentation: emitted by the chat/social service responsible for delivering chat events, with labels `{tenantId, channel_type}` and an explicit distinction between player-visible channels (global, zone, party) and system channels.

Environment and service docs that introduce new player-facing flows should:

- Reuse these SLIs where possible (for example by tagging `command_end_to_end_latency_ms` with a new `command` label), or
- Add new SLIs to this section so that operators have a single, authoritative list of player-centric targets.

Grafana dashboards under `design/observability/grafana` include a dedicated “Player Experience” dashboard that surfaces these SLIs for each environment and links back to the relevant runbooks when SLOs are breached.

### Degraded Modes and Observability Dependencies

Moderation and admin workflows should remain usable even when parts of the observability stack are degraded. To avoid coupling core actions to non‑authoritative systems:

- **Hard dependencies:** Logging & Admin Service itself, the domain services that own authoritative game data (for example, Game Session Service, Entity Management Service, Account Service), and Spring Cloud Gateway are required for core moderation and admin actions such as inspecting live sessions, muting, banning, or kicking players, or updating feature flags.
- **Soft dependencies:** Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager are treated as **best‑effort enrichments**. When any of these backends are unavailable or degraded, the Logging & Admin UI should:
  - Clearly indicate which data sources are unavailable (for example, “logs currently unavailable”, “metrics degraded”, or “traces unavailable”).
  - Continue to expose core moderation and admin APIs based on authoritative game data wherever possible.
  - Hide or disable only those features that require the missing backend (for example, embedded dashboards or historical trace searches), rather than failing the entire moderation workflow.
- **Alert routing:** If Alertmanager is unavailable, or email delivery is degraded, Logging & Admin should surface alert status inside its own UI and APIs so operators can still see pending alerts without relying solely on email or chat integrations. When Alertmanager is down, Logging & Admin may fall back to a small set of Prometheus recording rules that approximate critical alert conditions (for example, SLO breaches for tail-loss or player SLIs) and clearly label those views as “best-effort from Prometheus (Alertmanager unavailable)” so operators understand they are not seeing the full alert state.

New moderation features and admin tools must explicitly document:

- Which dependencies are required for the feature to function.
- How the feature behaves when observability systems are partially or fully unavailable.
- Whether any new metrics, logs, or traces introduced for the feature are considered hard requirements for safe operation, or are best‑effort enrichments similar to existing dashboards.

### Alert Taxonomy and Ownership

Alertmanager routes alerts based on a small, consistent label set so ownership and severity are always clear:

- Core labels:
  - `service` – owning service or component (for example `redis-coordination`, `game-session`, `tcp-proxy-service`, `postgres-backup`).
  - `component` – optional, for finer-grained subsystems (for example `tick`, `backup`, `coordination`).
  - `severity` – one of `P0`, `P1`, or `P2`.
  - `owner` – primary team or role responsible for triage (for example `platform`, `gameplay`, `web`, `infra`).
  - `runbook` – path to the relevant documentation section (for example `design/architecture/system-architecture-redis-incident-runbook.md#coordination-aof-tail-loss-slo-breach`).
- Severity guidelines:
  - **P0** – Player-visible outage or severe SLO breach for core flows (for example login unavailable, command latency outside SLO for a large fraction of players, sustained Redis tail-loss SLO breach affecting active regions).
  - **P1** – Degraded but tolerable behavior that should be addressed promptly (for example elevated cache evictions, slower tail-loss that remains within SLO but trends badly, missed backup or verification thresholds in a single environment).
  - **P2** – Non-urgent issues, capacity warnings, or minor errors that should be resolved during normal work.

Environment-specific Alertmanager configurations may add routing rules and notification channels, but they should preserve these labels and the `runbook` annotation so that:

- Logging & Admin can display alerts with clear ownership and links to the appropriate runbooks.
- Operators can jump from an alert to the corresponding Grafana dashboard and architecture/runbook section without guesswork.

### Alert Fallback Recording Rules

When Alertmanager is unavailable but Prometheus is still accessible, Logging & Admin may present a limited view of critical conditions based on recording rules evaluated directly in Prometheus. To keep behavior predictable, only a small set of fallback signals is supported:

- **Redis coordination tail-loss SLO breaches**
  - Recording rule based on `redis_coordination_tail_loss_ms{tenantId,regionId}` that classifies regions as inside or outside the 1–2 second envelope.
- **Tick execution safety ratios**
  - Recording rule that exposes `tick_execution_time_ms_p99 / tick_lock_ttl_ms` per region, using the recording rules defined in the Redis operations metrics catalog.
- **Login success ratio**
  - Recording rule mirroring the `LoginSuccessRatioLow` alert condition, based on `login_requests_total{outcome="success"}` vs `login_requests_total`.
- **Command p99 latency**
  - Recording rule mirroring the `CommandLatencyP99High` alert condition, based on `command_end_to_end_latency_ms_bucket`.

Logging & Admin should:

- Use these recording rules as the sole source of “active issues” when Alertmanager is unreachable, and clearly label the UI as “Alertmanager unavailable – showing fallback Prometheus conditions”.
- Prefer Alertmanager as the source of truth whenever it is healthy; fallback conditions are a last resort to keep operators informed of the most critical SLO violations.

For scripting and automation workloads, dashboards and alerts must include both live and dry-run activity:

- Live triggers and automation work are reported via metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total`, as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.
- Dry-run and test executions are tracked separately via `automation_script_test_runs_total` and `automation_script_test_runtime_seconds` so operators can see when validation tools are consuming significant sandbox resources even though they bypass mainline ScriptQuota and tenant automation budgets.

## Health Checks

- Spring Boot `/actuator/health` endpoints feed Kubernetes readiness and liveness probes.
- See [Deployment Environments](./infrastructure/deployment-environments.md#🩺-kubernetes-health-monitoring) for probe behavior.

## Error Tracking and Hotfixes

Logs in Kibana are searched daily for uncaught exceptions or repeated crashes. Alerts from Prometheus trigger on high error rates. When issues arise, operators follow the runbooks to deploy a hotfix image built from the `main` branch.

## Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
- [Redis Operations & Migrations](./system-architecture-redis-operations.md)
- [System Architecture Overview](./system-architecture-overview.md)
