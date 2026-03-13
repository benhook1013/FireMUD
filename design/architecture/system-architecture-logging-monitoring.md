# FireMUD Logging & Monitoring Overview

This document describes how FireMUD collects logs, metrics, and traces across all services, and how operators use those signals for debugging, moderation, and performance analysis.

---

## Logging Pipeline

- **Fluent Bit** sidecars collect service logs from every microservice.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries and embeds Kibana dashboards via its API for richer visualization.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`) and player context for moderation and incident drilldowns.
- At minimum, log events for request/tick-handling paths should include: `service`, `tenantId` (when known), `regionId` (when known), `traceId`, and `correlationId`. When a player is authenticated or a session is bound, logs should also include `playerId`.
- Kibana dashboards and saved searches filter by `tenantId`/`regionId` plus `traceId`/`playerId` so operators can scope incidents quickly without relying on ad-hoc message parsing.
- gRPC services use the shared `LoggingInterceptor` to include `traceId` and `correlationId` in every log entry. See [Shared Libraries](./system-architecture-shared-libraries.md).
- Log retention defaults to **14 days** in development and **90 days** in production, after which indices are archived. These values can be tuned via the [Deployment Environments](./infrastructure/deployment-environments.md) settings.
- Log storage hosts can be customized via the `FLUENT_ELASTICSEARCH_HOST` and `FLUENT_ELASTICSEARCH_PORT` environment variables ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability)).
- The local Docker Compose stack streams logs to the console by default and may optionally run a small observability stack (for example Prometheus/Grafana/Jaeger) for local debugging. Docker Compose is not treated as the canonical production observability environment; Kubernetes manifests under `k8s/monitoring` remain the source of truth for prod-like deployments.
- Operators search logs primarily through Kibana. Sample Grafana and Kibana dashboards live under [`design/observability`](../observability) and are described in [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md). The Logging & Admin Service provides a dedicated UI for moderation and audit trails.

### Log Service Identity Contract

To keep Kibana queries and alert triage consistent, log records and alert labels use different identity scopes:

- Log `service` is the runtime emitter identity (normally `spring.application.name` for services and explicit runtime names for infrastructure emitters such as `redis` or `redis-exporter`).
- Alert `service` may use runtime identity or canonical infra signal identity (for example `redis-coordination`, `postgres-backup`) for routing clarity.
- Do not use alert-only identities as log `service` values.
- For infra roles in logs, use additional structured fields (for example `component`, `redis_role`) rather than overloading log `service`.

## Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- The Logging & Admin Service queries Prometheus for metrics and Jaeger for trace analysis to power moderation dashboards and investigations. See [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md) for examples.
- It calls the Grafana API to embed existing dashboards alongside Kibana views for a unified operator experience.
- The service consumes Alertmanager notifications so operators can triage alerts inside the admin UI.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests. Traces are collected by an OpenTelemetry Collector and visualized with Jaeger. See [Tracing](./system-architecture-tracing.md) for deployment details.
- Sample Kubernetes manifests under [`k8s/monitoring`](../../k8s/monitoring) deploy the collector and Jaeger (`otel-collector.yaml`, `jaeger.yaml`).
- Metrics are recorded with Micrometer. The shared `MetricsInterceptor` tracks `grpc.server.requests` for each call. Services increment the `grpc.app_error` counter in their `error()` helpers as described in the [gRPC API Style guidelines](./system-architecture-grpc.md).
- Application metrics that are used in shared dashboards and alert rules must include a stable `service` label derived from `spring.application.name` so queries can be scoped (for example, `grpc_app_error_total{service="tcp-proxy-service"}`).
- Metric naming must match units: metrics and histograms with `_ms` in the name (for example `command_end_to_end_latency_ms_bucket`) are measured in **milliseconds** and must be compared against millisecond thresholds. If a metric is measured in seconds, it must use a `_seconds` name.
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
  - Quota and tick integration meters such as `script_quota_allowed_total`, `script_quota_denied_total`, `automation_tick_events_enqueued_total`, `automation_tick_version_fence_dropped_total`, and `automation_tick_plugin_version_fence_dropped_total`.
  - Sandbox and runtime meters such as `automation_script_sandbox_failures_total{reason=...}`, `automation_script_errors_total{tenantId, reason=...}`, and `automation_script_runtime_seconds`.
  - Dry-run/test isolation meters such as `automation_script_test_runs_total`, `automation_script_test_runtime_seconds`, and `automation_script_test_sandbox_failures_total`.
  - Plugin policy enforcement meters such as `automation_plugin_policy_violations_total`.
  These metrics are described in more detail in [System Architecture: Scripting & Automation](./system-architecture-scripting.md) and the [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md).
- **TCP Proxy metrics** capture Telnet DMZ behavior:
  - Connection meters such as `tcpproxy.connections.active` (Prometheus: `tcpproxy_connections_active`), `tcpproxy.connections.total` (Prometheus counter: `tcpproxy_connections_total`), and `tcpproxy.connections.limit.exceeded` (Prometheus counter: `tcpproxy_connections_limit_exceeded_total`) to surface global and per-IP caps derived from `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP`.
  - Safety and abuse meters such as `tcpproxy.telnet.discarded` (Prometheus counter: `tcpproxy_telnet_discarded_total`) (malformed Telnet negotiation, buffer overflows, repeated malformed `SESSION` envelopes, or invalid PROXY protocol headers) and `tcpproxy.tls.misconfig` (Prometheus counter: `tcpproxy_tls_misconfig_total`) (TLS startup errors). These allow operators to see when legacy Telnet clients, MCP tools, or misconfigurations are causing noisy connections at the DMZ boundary.
  - WebSocket bridge meters such as `tcpproxy.websocket.reconnects` (Prometheus counter: `tcpproxy_websocket_reconnects_total`) and timers like `tcpproxy.command` and `tcpproxy.idleClose` (Prometheus: `*_seconds` histograms) to track how often the proxy must establish a Proxy → Gateway WebSocket bridge and where time is spent in the Telnet pipeline.
  - Lifecycle and integration meters such as `tcpproxy.disconnect.notify.transport_failure{status="..."}` (Prometheus counter: `tcpproxy_disconnect_notify_transport_failure_total{status="..."}`) and `tcpproxy.disconnect.notify.app_error{code="..."}` (Prometheus counter: `tcpproxy_disconnect_notify_app_error_total{code="..."}`) to surface best-effort `NotifyDisconnect` delivery problems to the Game Session Service; these complement Game Session’s own session-takeover and resume counters described in the Reconnection Strategy doc and the TCP Proxy design’s **Service Interactions** section.
  Operators should create Grafana panels and Alertmanager rules that highlight sustained non-zero `tcpproxy_connections_limit_exceeded_total`, sharp increases in `tcpproxy_telnet_discarded_total`, and recurring `tcpproxy_disconnect_notify_transport_failure_total` spikes, since these typically indicate abusive clients, mis-tuned caps, TCP edge misconfiguration (for example bad PROXY headers), or issues on the Game Session side.
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
  - Recording rules and alerts for the bounded core-command SLO set must preserve the `command` label. An additional aggregate “all core commands” panel is allowed for high-level dashboards, but it must not be the sole paging signal because it can hide a single broken command behind healthy higher-volume commands.
  - Phase-split drilldown metrics must also be emitted for bounded command stages so operators can distinguish edge, dispatch, tick-wait, and domain-commit latency without depending entirely on traces. Use a histogram such as `command_latency_stage_ms_bucket{service,tenantId,regionId,command,stage,le}` where `stage` is a bounded enum such as `edge_queue`, `dispatch`, `tick_wait`, or `domain_commit`.
- **Telnet and WebSocket path availability**
  - SLI: fraction of successful connection attempts over total attempts for each entry path (Telnet and WebSocket). This SLI must be computed from an explicit attempts counter so it captures all failure modes, not just cap rejections.
  - SLO: ≥ 99.9% of connection attempts succeed over a 1-day window, evaluated per `tenantId` and `path`; sustained deviations are treated as P0 incidents for the affected entry path.
  - Instrumentation:
    - Edge services (TCP Proxy and Gateway) must emit `entrypath_connection_attempts_total{tenantId,path,outcome}` where:
      - `path` is a bounded enum (for example `telnet` or `websocket`).
      - `outcome` is a bounded enum (for example `success`, `limit_exceeded`, `auth_failed`, `upstream_unreachable`, `timeout`, `protocol_error`, `unknown`).
    - The SLI should be computed as `sum(rate(entrypath_connection_attempts_total{outcome="success"}[...])) / sum(rate(entrypath_connection_attempts_total[...]))` per `{tenantId,path}`.
    - Auxiliary meters such as `tcpproxy_connections_limit_exceeded_total` remain useful drilldowns but are not sufficient to define availability by themselves.
    - Prod-like environments must also run **independent synthetic probes** from outside the gameplay ingress boundary and export a low-cardinality metric such as `entrypath_blackbox_probe_success{path,target}`. These probes are the authoritative detection source for total entry-path outages that prevent traffic from ever reaching Gateway or TCP Proxy (for example LB, DNS, TLS, or ingress policy failures), while `entrypath_connection_attempts_total` remains the authoritative in-service breakdown for `outcome` analysis once traffic reaches the edge.
  - Alert routing:
    - Entry-path alerts should preserve `service` from the emitting series and include `component="entrypath"` so Telnet-path and WebSocket-path incidents can route and page independently.
  - Detection model:
    - Treat the 1-day availability window as the compliance/SLO view.
    - Also publish short-window recording rules and alerts (for example 5-minute and 30-minute availability or burn-rate views) so acute entry-path failures are detected quickly and do not wait for a 1-day window to move materially.
    - Blackbox probe alerts must exist alongside the in-service SLI alerts so “no requests reached the service” is still detected as a P0 edge-path outage.
- **Chat delivery latency**
  - SLI: time from chat message submission to delivery to all intended recipients, for example `chat_delivery_latency_ms` histogram keyed by `tenantId` and chat channel type.
  - SLO: 99% of chat messages are delivered in < 1s over a 5-minute window for active regions.
  - Instrumentation: emitted by the chat/social service responsible for delivering chat events, with labels `{tenantId, channel_type}` and an explicit distinction between player-visible channels (global, zone, party) and system channels.

Environment and service docs that introduce new player-facing flows should:

- Reuse these SLIs where possible (for example by tagging `command_end_to_end_latency_ms` with a new `command` label), or
- Add new SLIs to this section so that operators have a single, authoritative list of player-centric targets.

Grafana dashboards under `design/observability/grafana` include:

- `player-experience.json` – canonical SLO compliance dashboard for player SLIs.
- `player-experience-drilldown.json` – incident drilldown dashboard for per-outcome and per-command/channel investigation after an SLO breach.

### Player Experience Metrics Catalog (Contract)

The metrics below are treated as the canonical Prometheus-facing shapes for player experience SLIs/SLOs. Services may emit additional drilldown metrics, but dashboards and alerts should prefer these names and label sets:

- Login:
  - `login_requests_total{tenantId,regionId,outcome}` where `outcome` is a bounded enum (for example `success`, `invalid_credentials`, `rate_limited`, `upstream_error`, `timeout`, `unknown`).
- Commands:
  - `command_end_to_end_latency_ms_bucket{tenantId,regionId,command,le}` with `command` drawn from a documented, bounded command set for core SLO coverage.
    - Starting bounded set for SLO coverage (normalize synonyms/aliases in instrumentation): `move`, `look`, `combat`.
    - Alert rules and dashboards may filter to this bounded set (for example `command=~"move|look|combat"`) so the SLO signal stays stable even when new commands are introduced.
  - `command_latency_stage_ms_bucket{service,tenantId,regionId,command,stage,le}` for bounded stage-level drilldown. Required `stage` values are `edge_queue`, `dispatch`, `tick_wait`, and `domain_commit`; environment overlays may add a small number of additional bounded stages only with a design update here.
- Entry-path availability:
  - `entrypath_connection_attempts_total{tenantId,path,outcome}` with bounded enums for `path` and `outcome` as described above.
- Chat:
  - `chat_delivery_latency_ms_bucket{tenantId,channel_type,le}` with `channel_type` drawn from a bounded enum (global/zone/party/system, etc.).

### Degraded Modes and Observability Dependencies

Moderation and admin workflows should remain usable even when parts of the observability stack are degraded. To avoid coupling core actions to non‑authoritative systems:

- **Hard dependencies:** Logging & Admin Service itself, the domain services that own authoritative game data (for example, Game Session Service, Entity Management Service, Account Service), and Spring Cloud Gateway are required for core moderation and admin actions such as inspecting live sessions, muting, banning, or kicking players, or updating feature flags.
- **Soft dependencies:** Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager are treated as **best‑effort enrichments**. When any of these backends are unavailable or degraded, the Logging & Admin UI should:
  - Clearly indicate which data sources are unavailable (for example, “logs currently unavailable”, “metrics degraded”, or “traces unavailable”).
  - Continue to expose core moderation and admin APIs based on authoritative game data wherever possible.
  - Hide or disable only those features that require the missing backend (for example, embedded dashboards or historical trace searches), rather than failing the entire moderation workflow.
- **Alert routing:** If Alertmanager is unavailable, or email delivery is degraded, Logging & Admin should surface alert status inside its own UI and APIs so operators can still see pending alerts without relying solely on email or chat integrations. When Alertmanager is down, Logging & Admin may fall back to a small set of Prometheus recording rules that approximate critical alert conditions (for example, SLO breaches for tail-loss or player SLIs) and clearly label those views as “best-effort from Prometheus (Alertmanager unavailable)” so operators understand they are not seeing the full alert state.
  - For broader “observability stack outage” scenarios (Prometheus down, Elasticsearch down, Jaeger down), follow `design/architecture/system-architecture-observability-incident-runbook.md` for fallback workflows and recovery verification.

New moderation features and admin tools must explicitly document:

- Which dependencies are required for the feature to function.
- How the feature behaves when observability systems are partially or fully unavailable.
- Whether any new metrics, logs, or traces introduced for the feature are considered hard requirements for safe operation, or are best‑effort enrichments similar to existing dashboards.

### Alert Taxonomy and Ownership

Alertmanager routes alerts based on a small, consistent label set so ownership and severity are always clear:

- Core labels:
  - `service` – owning service identity for the alert source. For application alerts, use runtime identity derived from `spring.application.name` (for example `game-session-service`, `tcp-proxy-service`, `spring-cloud-gateway`). For infrastructure/exporter-backed alerts, use the canonical infra identity for that signal (for example `redis-coordination`, `postgres-backup`).
  - `component` – optional, for finer-grained subsystems (for example `tick`, `backup`, `coordination`).
  - `severity` – one of `P0`, `P1`, or `P2`.
  - `alert_class` – optional classifier for non-standard routing (for example `alert_class="test"` for smoke-test alerts that must never page).
  - `owner` – primary team or role responsible for triage (for example `platform`, `gameplay`, `web`, `infra`).
  - `runbook` – path to the relevant documentation section (for example `design/architecture/system-architecture-redis-incident-runbook.md#coordination-aof-tail-loss-slo-breach`).
- Severity guidelines:
  - **P0** – Player-visible outage or severe SLO breach for core flows (for example login unavailable, command latency outside SLO for a large fraction of players, sustained Redis tail-loss SLO breach affecting active regions).
  - **P1** – Degraded but tolerable behavior that should be addressed promptly (for example elevated cache evictions, slower tail-loss that remains within SLO but trends badly, missed backup or verification thresholds in a single environment).
  - **P2** – Non-urgent issues, capacity warnings, or minor errors that should be resolved during normal work.

Environment-specific Alertmanager configurations may add routing rules and notification channels, but they should preserve these labels and the `runbook` annotation so that:

- Logging & Admin can display alerts with clear ownership and links to the appropriate runbooks.
- Operators can jump from an alert to the corresponding Grafana dashboard and architecture/runbook section without guesswork.

#### Owner Catalog (Normative)

To prevent drift and “everyone owns it” ambiguity, `owner` must come from a bounded catalog:

- `platform` – observability stack, CI/CD, shared libraries, Kubernetes primitives, core reliability glue.
- `infra` – Redis/PostgreSQL operations, storage, networking, certificates, cluster health.
- `gameplay` – tick/runtime behavior, domain correctness, player-experience SLOs, region layout.
- `web` – web client and Gateway UX surface issues (route health, WebSocket behavior from the browser’s perspective).
- `security` – auth, token/JWKS, operator credentials, cross-service trust failures.

Routing rule requirements:

- Unknown `owner` values must be treated as a configuration error: route to the default operator channel and emit a warning annotation/log so it is fixed quickly.
- In single-admin deployments, it is acceptable for all `owner` values to route to the same notification destination; `owner` still matters for triage context and for future multi-operator setups.

Service label requirements:

- `service` values in alerts must come from the canonical service catalog:
  - Runtime services: `spring.application.name` identities.
  - Infra/exporter services: documented infra identities such as `redis-coordination` and `postgres-backup`.
  Avoid ad-hoc domain names.
- If an alert expression intentionally spans multiple runtime services (for example, entry-path SLIs across Gateway and TCP Proxy), do not hardcode a single `service` label in rule metadata. Keep `service` from the metric series and use `component` (for example `component="entrypath"`) to group routing and dashboards.

Alert owner mapping guidelines:

- Redis coordination health, tail-loss, AOF growth, and failover alerts: `owner="infra"`.
- PostgreSQL backup/restore and backup-pause safety alerts: `owner="infra"`.
- Tick behavior and gameplay-flow correctness alerts (for example replay storms, ledger backlogs, unsafe tick runtime ratios): `owner="gameplay"`.
- Observability stack availability/routing alerts (Prometheus, Alertmanager, Elasticsearch, Jaeger, Grafana): `owner="platform"`.

Player SLO owner mapping (normative):

- Login success ratio alerts (`LoginSuccessRatioLowGateway`, `LoginSuccessRatioLowTcpProxy`): `owner="platform"` (ingress/auth availability domain).
- Entry-path availability alerts (`EntryPathAvailabilityLowGateway`, `EntryPathAvailabilityLowTcpProxy`): `owner="platform"` (edge connectivity domain).
- Command latency alerts (`CommandLatencyP99HighGateway`, `CommandLatencyP99HighTcpProxy`): `owner="gameplay"` (in-session runtime performance domain).
- Chat delivery latency alerts (`ChatDeliveryLatencyP99High`): `owner="gameplay"` (player-facing runtime behavior domain).

### Alert Fallback Recording Rules

When Alertmanager is unavailable but Prometheus is still accessible, Logging & Admin may present a limited view of critical conditions based on recording rules evaluated directly in Prometheus. To keep behavior predictable, only a small set of fallback signals is supported:

- **Redis coordination tail-loss SLO breaches**
  - Recording rules based on `redis_coordination_tail_loss_ms{tenantId,regionId}` that expose both `redis_coordination_tail_loss_budget_ms{tenantId,regionId}` and a derived breach indicator such as `redis_coordination_tail_loss_slo_breached{tenantId,regionId}` using the canonical envelope (`tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)`).
- **Tick execution safety ratios**
  - Recording rule that exposes `tick_execution_time_ms_p99 / tick_lock_ttl_ms` per region, using the recording rules defined in the Redis operations metrics catalog.
- **Login success ratio**
  - Recording rules mirroring `LoginSuccessRatioLowGateway` and `LoginSuccessRatioLowTcpProxy`, scoped by `service` and based on `login_requests_total{outcome="success"}` vs `login_requests_total`.
- **Command p99 latency**
  - Recording rules mirroring `CommandLatencyP99HighGateway` and `CommandLatencyP99HighTcpProxy`, scoped by `service` and preserving the bounded `command` label for the core-command SLO set, based on `command_end_to_end_latency_ms_bucket`.
- **Entry-path availability**
  - Recording rules mirroring both the short-window detection view and the 1-day compliance view for `EntryPathAvailabilityLowGateway` and `EntryPathAvailabilityLowTcpProxy`, scoped by `service` and `path`, based on `entrypath_connection_attempts_total`.
- **Chat delivery latency**
  - Recording rule mirroring `ChatDeliveryLatencyP99High`, based on `chat_delivery_latency_ms_bucket` with per-tenant/channel dimensions preserved.
- **Backup health**
  - Recording rules mirroring missed backup, missed verification, and scoped pause-budget breaches, based on `backup_last_success_timestamp_seconds`, `backup_verify_last_success_timestamp_seconds`, `backup_tick_pause_wait_seconds`, `backup_tick_pause_duration_seconds`, and their matching emitted budget gauges.
  - Canonical fallback recordings should expose at least:
    - `backup_pipeline_recent_backup_slo_breached`
    - `backup_pipeline_recent_verification_slo_breached`
    - `backup_tick_pause_wait_budget_breached{scope_type,tenantId,regionId}`
    - `backup_tick_pause_duration_budget_breached{scope_type,tenantId,regionId}`
    - `backup_ticks_paused_budget_breached{scope_type,tenantId,regionId}`

Logging & Admin should:

- Use these recording rules as the sole source of “active issues” when Alertmanager is unreachable, and clearly label the UI as “Alertmanager unavailable – showing fallback Prometheus conditions”.
- Prefer Alertmanager as the source of truth whenever it is healthy; fallback conditions are a last resort to keep operators informed of the most critical SLO violations.
- Treat broader observability-stack outages as only partially representable in fallback mode: Alertmanager-specific/routing conditions can still be surfaced when Prometheus is healthy, but Prometheus-down conditions cannot be reconstructed from fallback rules because the source of truth is itself unavailable.

Fallback recording rules must be installed as part of the Prometheus ruleset for every prod-like environment. The reference starting point for these rules lives at `k8s/monitoring/prometheus-rules-firemud.yaml`; environment overlays may adjust thresholds but must preserve the metric names, labels, and alert label contract described in this document.

### Observability Stack Alerts

Observability backends are best-effort enrichments for gameplay and moderation workflows, but they still require first-class alerts because their failure can mask player-visible incidents and break operator triage.

- Prod-like environments must also provide an **independent meta-monitoring path** outside the Prometheus + Alertmanager failure domain:
  - A deadman/heartbeat signal from the in-cluster monitoring stack to an external notification sink, or an equivalent externally hosted monitor.
  - External liveness checks for Prometheus, Alertmanager, Grafana, Elasticsearch/Kibana, and Jaeger/OpenTelemetry Collector entrypoints.
  - This independent path is required because Prometheus cannot reliably page on its own total outage.
- Prod-like environments must install a canonical `platform`-owned alert set for:
  - Prometheus rule evaluation or scrape health problems.
  - Alertmanager routing/configuration failures.
  - Elasticsearch indexing or cluster-health failures.
  - Fluent Bit output/backpressure failures.
  - OpenTelemetry Collector export failures.
  - Jaeger query/storage availability failures.
  - Grafana datasource or service availability failures.
- These alerts should use `owner="platform"` and `runbook="design/architecture/system-architecture-observability-incident-runbook.md#..."`.
- Reference alert names should remain stable across overlays so runbooks and Logging & Admin can key off them predictably. The canonical starting set is:
  - `PrometheusRuleEvaluationsFailing`
  - `PrometheusServiceDiscoveryFailures`
  - `AlertmanagerServiceUnavailable`
  - `AlertmanagerNotificationsFailing`
  - `AlertmanagerConfigReloadFailed`
  - `ElasticsearchClusterHealthRed`
  - `ElasticsearchIndexingFailuresHigh`
  - `FluentBitOutputErrorsHigh`
  - `OTelCollectorUnavailable`
  - `OTelCollectorExportFailures`
  - `JaegerQueryUnavailable`
  - `JaegerStorageFailuresHigh`
  - `GrafanaDatasourceUnavailable`
  - `GrafanaServiceUnavailable`
- Environment overlays may adapt metric expressions to local exporter/job naming, but they should preserve the canonical alert names and routing labels so Logging & Admin and incident docs remain consistent.
- The in-cluster alert set above complements, but does not replace, the required independent meta-monitoring path for total observability-stack outages.

For scripting and automation workloads, dashboards and alerts must include both live and dry-run activity:

- Live triggers and automation work are reported via metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total`, as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md` and `design/architecture/system-architecture-scripting-observability-contract.md`.
- Dry-run and test executions are tracked separately via `automation_script_test_runs_total` and `automation_script_test_runtime_seconds` so operators can see when validation tools are consuming significant sandbox resources even though they bypass mainline ScriptQuota and tenant automation budgets.
- Do not label metrics with high-cardinality identifiers such as `scriptEventId`; use logs/traces and `script_event_audit` queries for per-event correlation.

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
