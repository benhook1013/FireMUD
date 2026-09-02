# FireMUD Logging & Monitoring Overview

This document describes how FireMUD collects logs, metrics, and traces across all services, and how operators use those signals for debugging, moderation, and performance analysis.

The observability owner contracts include [ADR 0158: simplified observability degradation without fallback alert authority](./decisions/adr-0158-simplified-observability-degradation-without-fallback-alert-authority.md), [ADR 0159: profile-dependent independent deadman and public-path monitoring](./decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md), [ADR 0160: staged profile-aware player-experience SLO contract](./decisions/adr-0160-staged-profile-aware-player-experience-slo-contract.md), [ADR 0161: profile-aware isolated synthetic player-flow canaries](./decisions/adr-0161-profile-aware-isolated-synthetic-player-flow-canaries.md), [ADR 0162: profile-aware asynchronous end-to-end log-queryability evidence](./decisions/adr-0162-profile-aware-asynchronous-end-to-end-log-queryability-evidence.md), and [ADR 0167: allowlisted sensitive trace attributes](./decisions/adr-0167-allowlisted-sensitive-trace-attributes.md). This document owns monitoring and evidence consequences while authoritative domain/control-plane state remains outside the observability stack.

For instance-bound gameplay/runtime records, scripting observability must retain the exact `(scriptPatchVersion, scriptPinEpoch)` in structured audit/log/trace records and must distinguish Game Session rollout-history authority from Automation readiness/convergence projections. An authoritative `UNPINNED` result is recorded as the valid no-script state only when `scriptPatchVersion`, `scriptPinEpoch`, and the current-pin `controlPlaneRequestId` are all absent; partial presence or unavailable/incomplete owner evidence is `UNAVAILABLE` and must not be classified as `UNPINNED` or inferred from missing authority. Tenant-readiness `onLoad` retains its declared pre-instance-pin identity and omits `gameInstanceId` and `scriptPinEpoch`; those fields must not be populated with sentinels. Metric names, labels, and increment units remain owned by the [normative scripting tables](./system-architecture-scripting-normative-contract-tables.md); any later scripting metric lists here are non-authoritative operational mirrors for bounded dashboards and alerts, not duplicate definitions. This document owns only bounded monitoring, dashboard, and alerting consequences. Routine script rollback is an epoch-fenced workflow, not a gameplay-tick outage signal.

For the canonical definition of environment classes and which ones are considered player-facing or prod-like, see [Deployment Environments](./infrastructure/deployment-environments.md#terms) and [Deployment Environments](./infrastructure/deployment-environments.md#canonical-environment-classes). In this document, “prod-like” means `hobby-self-hosted`, `staging`, and `production` unless a section explicitly narrows the requirement further.

## Implementation Notes

The current implemented baseline is narrower than the full target-state observability contract:

- Runtime identity, startup logging, shared HTTP/gRPC request logging, bounded WebSocket/Telnet handler logging, `grpc.app_error`, and the first bounded gameplay command counters are implemented.
- Current Logging & Admin queries only its PostgreSQL `log_events` domain table; profile-aware emitter-to-query execution and Elasticsearch/Kibana or compatible-backend queryability proof remain unimplemented.
- Raw runtime or gameplay identifiers such as `serviceInstanceId`, `tenantId`, `sessionId`, `characterId`, and `script_patch_version` are not approved as ordinary Prometheus metric labels. These identifiers belong in structured logs and traces unless a future architecture update records a narrow low-cardinality exception. Required mode-aware metric families must use their documented bounded labels and must not add these identifiers.
- The player-experience SLI catalog below is a target-state metric contract. It describes the operator-visible SLO surface FireMUD wants, but it is not fully implemented by the current services. Before implementing any metric that needs tenant, game-instance, or region scoping, reconcile the label shape with the cardinality policy, for example through a bounded environment-specific scope label or an explicitly documented exception.
- Synthetic player-flow canaries, the independent deadman/heartbeat mirror, and the related target-state canary alert fixtures have an operator-run runtime harness in `dev-tools/observability/run-player-experience-smoke.py`. The harness currently fails closed to `playerFlowCanary=omitted`: no shipped Account-owned verifier binds synthetic classification, non-default credentials, isolated per-transport characters, analytics/SLO exclusion, and actual execution to retained evidence. A local identity JSON cannot elevate a canary to `advertised`; the validator rejects advertised retained evidence until that authoritative verifier exists. The repository also does not schedule the harness continuously or publish a deployment-owned expected-series inventory, so a runner that stops before its first emission remains an implementation gap rather than a condition proved by an installed alert. The shared PrometheusRule and current profile overlays withhold the `PlayerFlowCanary*` alert family until a deployment-owned `playerFlowCanary=advertised` applicability/expected-series gate and safe advertised-to-omitted transition cleanup exist. The same inventory gap applies to public-path blackbox mirror alerts: the current independent-required published overlay installs deadman rules only and does not install path-specific zero or absent rules that could misclassify a non-exposed path. The authoritative external pager deployment remains environment-specific.
- Validation and runtime-proof selection is owned by [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md); this document defines only observability evidence and monitoring consequences.
- The current smoke harness emits the bounded canonical metric targets `gateway` for WebSocket and `tcp_proxy` for Telnet. Hosts, URLs, deployment identifiers, and runtime identifiers remain configuration/evidence details rather than metric labels.
- Routine backup dashboards and alert snippets use artifact freshness, lineage, integrity/readability, and current recovery-convergence signals. Tick-pause panels remain maintenance/reset views only and are not routine backup health signals.
- The measured Coordination Redis exposure is `redis_unreplicated_write_window_ms{scope}`, compared with the configured measured-exposure target `redis_unreplicated_write_window_slo_ms{scope}` and, when implemented and proved, its breach series `redis_unreplicated_write_window_slo_breached{scope}`. The cadence-derived compatibility pair `redis_coordination_tail_loss_budget_ms{scope}` and `redis_coordination_tail_loss_slo_breached{scope}` is currently unavailable: no checked-in Prometheus rule emits it because no deployment metrics producer currently emits the required `tick_interval_ms{scope,scope_class}` input. The expressions remain target-only templates until that producer and bounded-label contract are implemented and proved. Alerting must not treat either pair as currently emitted or as measured-SLO proof.

---

## Logging Pipeline

- Every deployment profile declares its supported log collection and operator-query posture. The default indexed profile uses **Fluent Bit** to collect service logs, stores them under the `firemud-logs-*` convention in **Elasticsearch**, and exposes **Kibana** searches and dashboards.
- A compatible indexed backend may replace Fluent Bit, Elasticsearch, or Kibana only with a documented mapping for collection, required fields, supported query path, configured queryability delay, scoped read access, retained evidence, and degraded-state behavior under the [Log Pipeline Queryability Contract](#log-pipeline-queryability-contract).
- A hobby, single-node, or small profile may instead support console or journal retrieval, or explicitly mark indexed search unavailable. Such a profile records the reduced operator posture and does not install, expose, or claim Kibana/indexed-query capabilities that it omits.
- **Target state:** The **Logging & Admin Service** exposes moderation tools and the query/dashboard capabilities advertised by the selected profile. Kibana embedding is the default indexed-profile integration, not a universal dependency. The current service exposes only its narrower PostgreSQL-backed log query and does not implement the selected-profile query client, embedded dashboards, or a separate admin UI.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`) and gameplay identity context for moderation and incident drilldowns.
- At minimum, log events for request/tick-handling paths should include: `service`, `tenantId` (when known), `gameInstanceId` (when known), `regionId` (when known), `traceId`, and `correlationId`. When a player is authenticated or a session is bound, logs should also include `characterId`.
- The supported operator query path filters by `tenantId`/`gameInstanceId`/`regionId` plus `traceId`/`characterId` when those fields apply, so operators can scope incidents quickly without relying on ad-hoc message parsing. The default indexed profile supplies Kibana dashboards and saved searches for those queries; a compatible backend maps equivalent searches.
- gRPC services use the shared `LoggingInterceptor` to include `traceId` and `correlationId` in every log entry. See [Shared Libraries](./system-architecture-shared-libraries.md).
- The default indexed profile starts with **14 days** of online retention in development and **90 days** in production. Each profile defines a finite retention policy and whether eligible records are deleted or moved to a declared archive with explicit retrieval semantics; an archive is not silently treated as online queryability evidence. These values are configurable through [Deployment Environments](./infrastructure/deployment-environments.md) and remain deployment policy rather than universal durations.
- Default-profile Elasticsearch hosts can be customized via the `FLUENT_ELASTICSEARCH_HOST` and `FLUENT_ELASTICSEARCH_PORT` environment variables ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability)); compatible and reduced profiles document their own collector/query configuration or explicit omission.
- The local Docker Compose stack streams logs to the console by default and may optionally run a small observability stack (for example Prometheus/Grafana/Jaeger) for local debugging. Docker Compose is not treated as the canonical production observability environment; Kubernetes manifests under `k8s/monitoring` remain the source of truth for prod-like deployments.
- Operators use the profile's supported query path: Kibana for the default indexed profile, the mapped equivalent for a compatible indexed backend, or the declared console/journal path for a reduced profile. Sample Grafana and Kibana assets live under [`design/observability`](../observability) and are described in [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md). **Target state:** Logging & Admin provides a dedicated moderation and audit UI independently of whether indexed log search is advertised; no separate admin UI or embedded-query endpoint exists currently.

### Log Service Identity Contract

To keep Kibana queries and alert triage consistent, log records and alert labels use different identity scopes:

- Log `service` is the runtime emitter identity (normally `spring.application.name` for services and explicit runtime names for infrastructure emitters such as `redis` or `redis-exporter`).
- Alert `service` may use runtime identity or canonical infra signal identity (for example `redis-coordination`, `postgres-backup`) for routing clarity.
- Do not use alert-only identities as log `service` values.
- For infra roles in logs, use additional structured fields (for example `component`, `redis_role`) rather than overloading log `service`.

### Runtime Instance Identity Contract

FireMUD distinguishes logical service identity from the identity of one running process instance:

- `service` is the logical service name and remains stable across replicas and restarts (normally `spring.application.name`).
- `serviceInstanceId` identifies one running microservice instance and must be unique for that runtime instance.
- `serviceInstanceId` should come from true runtime identity when available (for example pod or container identity surfaced by the platform). When the runtime does not provide one, the service should generate a unique startup-time identifier.
- Do not treat `serviceInstanceId` as static operator config by default. It is not a cluster/site/node label and should not be manually hardcoded as the normal model for replicated services.
- Optional additional runtime fields such as `hostname`, `buildVersion`, `buildSha`, `imageTag`, or `bootedAt` may be emitted alongside `serviceInstanceId` when available.

At minimum, player-path and request-handling logs should include:

- `service`
- `serviceInstanceId`
- `traceId`
- `correlationId`

When known, logs should also include gameplay identity fields such as:

- `tenantId`
- `regionId`
- `gameInstanceId`
- `characterId`

These gameplay fields should be attached through shared logging-context helpers where possible rather than repeated ad hoc message formatting. The current bounded baseline now covers the highest-value command/admission paths plus reconnect replay/refresh and live communication-recipient delivery when a bound gameplay context is already known.

Every service should also emit one structured startup lifecycle log that includes:

- `service`
- `serviceInstanceId`
- active profiles
- build/version metadata when available

This startup event exists to make restart correlation and incident drilldown easier without requiring operators to infer process identity only from pod names or timestamps.

### Request-Path Logging Baseline and Bounded Exceptions

The `02.14` shared logging baseline is now canonical for the main request-handling paths:

- Shared servlet HTTP logging should carry the runtime identity and request-correlation field set described above.
- Shared reactive HTTP logging should carry the same baseline field set.
- Shared gRPC server logging should carry the same baseline field set.
- Gateway gameplay-admission logs that run in the custom handshake filter before WebSocket upgrade should normalize through the shared runtime logging context as well, using the gateway request ID or transport-session ID as stable correlation when available.
- Custom WebSocket edge handlers that bypass the shared HTTP filters should normalize their local logs through the shared runtime logging context so runtime identity and stable connection-correlation fields are still present.
- Telnet edge handlers in `tcp-proxy-service` should do the same so the main player-facing edge protocols share one runtime identity and connection-correlation contract.

This baseline is intentionally bounded:

- Full reactive-request MDC propagation outside the shared filters is not required for the canonical contract as long as the shared request logs and major handler logs carry the standard field set.
- These bounded exceptions do not change the rule that runtime identity and request correlation must be present on the canonical HTTP, gRPC, and WebSocket request paths.

### Runtime Identity Exposure

Runtime identity should be exposed consistently enough that operators and admin tooling can confirm which process is serving traffic:

- Logs should carry the runtime identity fields described above.
- Services should expose the same logical identity and runtime instance identity through a lightweight runtime-info surface (for example Spring Boot `info` contributors or an equivalent admin/debug endpoint).
- Request correlation and runtime identity are separate concerns:
  - `traceId` and `correlationId` follow one request or call chain.
  - `serviceInstanceId` identifies which running service instance handled that work.

### Metrics Cardinality Rule For Runtime Identity

`serviceInstanceId` should not be added to most Prometheus metrics by default:

- Keep `serviceInstanceId` in logs and traces first.
- Add it to metrics only for narrowly justified low-volume diagnostic signals.
- The standard metric `service` label should remain the bounded logical service identity rather than a per-instance identifier.
- No runtime-identity metric-label exceptions are currently approved in the canonical FireMUD metrics set.
- If a future low-volume diagnostic metric truly needs a per-instance runtime label, the exception must be documented in this section before it is treated as canonical.

## Metrics & Tracing

- In profiles that install them, **Prometheus** scrapes service metrics and supplies alert evaluation inputs to **Alertmanager**, while **Grafana** visualizes performance data.
- **Target selected-profile integration:** Logging & Admin queries the profile's supported metrics and trace backends and presents its supported dashboard and routed-alert views. The default reference profile uses Prometheus, Jaeger, Grafana, Kibana, and Alertmanager; a compatible profile maps equivalent supported paths, while an omitted capability is `not_applicable`. See [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md) for examples.
- **Current Logging & Admin boundary:** the service has no Prometheus, Jaeger, Grafana, Kibana, or Alertmanager client, embedded-dashboard endpoint, or separate admin UI. Its current log-query surface reads only its PostgreSQL `log_events` table and is not a selected-profile observability integration.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests. Traces are collected by an OpenTelemetry Collector and visualized with Jaeger. See [Tracing](./system-architecture-tracing.md) for deployment details.
- Sample Kubernetes manifests under [`k8s/monitoring`](../../k8s/monitoring) deploy the collector and Jaeger (`otel-collector.yaml`, `jaeger.yaml`).
- Metrics are recorded with Micrometer. The shared `MetricsInterceptor` tracks `grpc.server.requests` for each call. Services increment the `grpc.app_error` counter in their `error()` helpers as described in the [gRPC API Style guidelines](./system-architecture-grpc.md).
- Application metrics that are used in shared dashboards and alert rules must include a stable `service` label derived from `spring.application.name` so queries can be scoped (for example, `grpc_app_error_total{service="tcp-proxy-service"}`).
- Metric naming must match units: metrics and histograms with `_ms` in the name (for example `command_end_to_end_latency_ms_bucket`) are measured in **milliseconds** and must be compared against millisecond thresholds. If a metric is measured in seconds, it must use a `_seconds` name.
- Business methods in services are annotated with `@Timed` to publish custom Prometheus timers.
- Most services expose a `/actuator/prometheus` endpoint for metrics. Scrape intervals are tuned per environment (typically 15s in development and 30s in production).
- Metrics for Redis are collected via the [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment, and a PostgreSQL exporter is available for database metrics. Redis dashboards surface available exporter/coordination metrics; target-state panels for Lua script latency, lock contention, retry queue depth, keyspace hits/misses, eviction rates, and latency percentiles for tick-related commands remain currently unavailable until their producers and selected-profile wiring exist. The minimum Redis SLOs and alert wiring are defined in [Redis Operations & Migrations](./system-architecture-redis-operations.md#redis-slos--budgets), which summarizes the core metrics and alerts that must be wired for each Redis role.
  - Additional application metrics track:
    - Failed lock acquisitions as bounded operational rollups (for example `redis_tick_lock_acquire_failed`) to highlight contention classes; exact region hotspots come from runtime-health/control-plane records and structured logs.
    - The ratio of replayed ticks to total ticks (for example `gamesession_tick_replayed_total` vs `gamesession_tick_executed_total`) so operators can see when idempotent recovery paths are being exercised frequently.
    - Tick runtime safety margins use metric-family signatures (non-executable notation) such as `tick_execution_time_ms_bucket{scope_class,tick_mode,le}` and derived recording rules such as `tick_execution_time_ms_p95{scope_class,tick_mode}` and `tick_execution_time_ms_p99{scope_class,tick_mode}`. Because TTL families do not carry `tick_mode`, the bounded `scope_class` safety ratios materialize each branch's constant mode with `label_replace` and match with `on (scope_class, tick_mode)`: normal uses `tick_execution_time_ms_p99{scope_class,tick_mode="normal"} / on (scope_class,tick_mode) label_replace(tick_lock_ttl_ms{scope_class},"tick_mode","normal","scope_class",".*")`, while solo uses `tick_execution_time_ms_p99{scope_class,tick_mode="solo"} / on (scope_class,tick_mode) label_replace(solo_lock_ttl_ms{scope_class},"tick_mode","solo","scope_class",".*")`. These families and dashboards are target-only/currently unavailable until Game Session emits and proves the required bounded labels; absent or stale series are unknown. When implemented, a class-level ratio that regularly exceeds a configured fraction of its selected TTL (for example `0.5×` for warning, `0.75×` for critical) is a detection/escalation signal surfaced in dashboards; exact region health and any **degraded** classification come from authoritative runtime-health/control-plane lookup.
    - Lock refresh usage, via counters such as `redis_tick_lock_refresh_requests_total` and `redis_tick_lock_refresh_denied_total`, so operators can detect commands that routinely rely on the optional lock refresh helper instead of completing within the normal lock TTL.
- **Automation & Scripting metrics** surface scheduler, quota, and sandbox behavior:
  - Scheduler and budget meters such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `automation_script_queue_delay_seconds`, `automation_script_leadership_changes_total`, and bounded-scope budget counters such as `automation_script_tenant_budget_allowed_total{scope, tier}` / `automation_script_tenant_budget_denied_total{scope, tier}`.
  - Quota and tick integration meters such as `script_quota_allowed_total`, `script_quota_denied_total`, `automation_tick_events_enqueued_total`, `automation_tick_version_fence_dropped_total`, and `automation_tick_plugin_version_fence_dropped_total` with bounded semantic dimensions only.
  - Sandbox and runtime meters such as `automation_script_sandbox_failures_total{reason=...}`, `automation_script_errors_total{scope, reason=...}`, and `automation_script_runtime_seconds`.
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
- For the data-driven `LOOK` path, see [LOOK instrumentation](../project-management/slice-support/look-instrumentation.md) for the specific `gamesession.command.look.*` meters, log conventions, and tracing guidance.

### Cardinality Guardrails for Metrics

FireMUD’s metrics are designed around low- and medium-cardinality labels so dashboards and alerts remain reliable even at scale. To keep this consistent:

- Allowed labels typically include bounded values such as `service`, `job`, `grpc_method`, `status`, `code`, `redis_role`, `effect_type`, `outcome`, `type`, `reason`, and other explicitly documented bounded dimensions in the Redis, tick, and gRPC architecture docs.
- Disallowed labels on ordinary gameplay/session counters and timers include raw runtime, tenant, request, session, player, or entity identifiers such as `serviceInstanceId`, `tenantId`, `regionId`, `traceId`, `spanId`, `characterId`, `sessionId`, `gameInstanceId`, `script_patch_version`, and arbitrary error messages or stack traces. These belong in logs and traces unless a specific operational metric contract explicitly approves them.
- Existing Redis, tick, backup, and control-plane operational metrics may retain explicitly documented scope labels only when those labels are bounded operational buckets such as `scope`, `region_class`, or `operation`, not raw player/runtime identifiers.
- No canonical metrics exception currently approves raw `tenantId` bucketing on ordinary Prometheus series. If a player SLO truly needs tenant, game-instance, or region drilldown, introduce an explicitly bounded `scope` label or record a narrow architecture exception here before adding new producers, dashboards, or alerts.
- When in doubt, prefer coarser labels (for example `error_code` from a bounded enum or small string set) and aggregate multiple rare values into an `other` bucket rather than exposing them as unbounded labels.
- New metrics must document their label sets in the relevant architecture or service README and confirm that they conform to these guardrails before being added to dashboards or alerts.

### Canonical Bounded Metrics Scope

The `scope` label is a bounded operational bucket, never a raw `tenantId`, `gameInstanceId`, `regionId`, player, session, or entity identity. Each metric family must document its bounded scope values, such as a deployment-wide `environment` bucket or an approved `region_class`; references to tenant/game-instance/region scope mean such an operational bucket, not the raw runtime tuple. Exact runtime diagnosis remains on control-plane/runtime-health reads, structured logs, and audit records.

Pre-gameplay metrics such as login success and entry-path availability must use the deployment-wide baseline unless an approved non-runtime bucket is available. They must not require `gameInstanceId`, `regionId`, or another field that is unavailable before `PLAY` selects gameplay scope.

### Player Experience SLIs and SLOs (Target-State Contract)

In addition to infrastructure-level SLOs for Redis, ticks, and backup pipelines, FireMUD tracks a small set of player-centric SLIs. The SLI families are the target contract; the numeric values below are initial calibration objectives rather than release promises, promotion gates, or universal paging thresholds. A hosted profile may promote a value to an enforced objective only after representative measurement defines the eligible event population, authoritative completion boundary, bounded scope, minimum-sample behavior, and multi-window burn policy. Hobby and small profiles expose these values informationally unless they explicitly claim managed availability.

The current services emit narrower gameplay and edge metrics; do not treat the metric names in this section as implemented until producers and smoke checks exist. For every SLI recording and evaluation, the state is `unknown` when the eligible denominator is zero, the eligible sample count is below the profile's minimum, or any required evidence input is `unknown`, regardless of whether that profile otherwise permits unknown evidence to propagate; a profile may omit optional evidence, but it must not coerce an unknown required input into a healthy, compliant, or breached result. `unknown` is an explicit non-SLI state and must never be classified as healthy, compliant, breached, or zero-percent availability. User mistakes, invalid credentials, malformed or semantically invalid requests, intentional policy denials, and traffic rejected as abuse are reported separately rather than counted as server-attributable availability failures. Platform timeouts, required-dependency failures, internal errors, and incorrectly rejected eligible traffic do count.

- **Login success ratio**
  - SLI: fraction of successful eligible login attempts over eligible attempts, aggregated over the same 15-minute window per bounded `scope` and canonical ingress `service`: `sum by (scope, service) (rate(login_requests_total{outcome="success"}[15m])) / sum by (scope, service) (rate(login_requests_total{outcome=~"success|server_failure"}[15m]))`. `user_rejection` and `policy_rejection` are ineligible and therefore excluded from both numerator and denominator; malformed traffic is `user_rejection`, while abuse/rate-limit rejection is `policy_rejection`.
  - The primary ratio must not sum duplicate observations from multiple hops. A deployment-wide rollup may aggregate only disjoint canonical ingress emitters after their path ownership is defined; service-specific ratios remain the diagnostic source of truth.
  - Calibration starting point: ≥ 99.5% success over a 15-minute rolling window for eligible attempts, evaluated per documented bounded `scope` after applying the profile's minimum-sample policy.
  - Instrumentation: target-state instrumentation should be emitted by Spring Cloud Gateway (and any protocol-bridging entry points such as the TCP Proxy) for login-related routes, with bounded labels for outcome and canonical bounded `scope`. The pre-gameplay baseline is `scope="environment"`; do not add raw `tenantId`, `gameInstanceId`, or `regionId` labels.
- **Command end-to-end latency**
  - SLI: gateway-to-domain command latency, measured from acceptance of an authenticated, admitted command at Gateway or TCP Proxy after bounded parsing and policy checks through to the authoritative terminal command result needed for outbound projection (including domain commit), for example `command_end_to_end_latency_ms` histogram with bounded labels `service`, `scope`, and `command`.
  - Calibration starting point: 99% of eligible core gameplay commands complete in < 250ms over a 5-minute window, per documented bounded `scope`. A profile must replace or qualify this value when its declared authoritative command-completion boundary legitimately includes a longer tick wait or other intentional scheduling delay; queue acceptance is not completion.
  - Instrumentation: target-state instrumentation should be emitted by Gateway (and optionally the TCP Proxy for Telnet) with bounded labels for `service`, command, and a documented bounded `scope`. Core commands such as movement, LOOK, and combat should use a small, documented set of `command` label values so per-service/per-command latency panels remain low-cardinality.
  - Recording rules and alerts for the bounded core-command SLO set must preserve the bounded `service` and `command` labels. An additional aggregate “all core commands” panel is allowed for high-level dashboards, but it must not be the sole paging signal because it can hide a single broken command behind healthy higher-volume commands or another service.
  - Phase-split drilldown metrics must also be emitted for bounded command stages so operators can distinguish edge, dispatch, tick-wait, and domain-commit latency without depending entirely on traces. Use a histogram such as `command_latency_stage_ms_bucket{service,scope,command,stage,le}` where `scope` uses the metric family's documented bounded values and `stage` is a bounded enum such as `edge_queue`, `dispatch`, `tick_wait`, or `domain_commit`.
- **Telnet and WebSocket path availability**
  - SLI: fraction of successful eligible connection attempts over eligible attempts for each entry path (Telnet and WebSocket). This SLI must be computed from an explicit attempts counter so it captures eligible platform failure modes, not just cap rejections. `success` and `server_failure` are eligible; `user_rejection` and `policy_rejection` are excluded, including malformed protocol traffic and abuse/rate-limit rejection.
  - Calibration starting point: ≥ 99.9% of eligible connection attempts succeed over a 1-day window, evaluated per approved scope and `path`. A sustained, high-confidence outage may become P0; a low-volume ratio alone does not.
  - Instrumentation:
    - Edge services (TCP Proxy and Gateway) must emit a bounded attempts counter such as `entrypath_connection_attempts_total{service,scope,path,outcome}` where:
      - `service` is the bounded emitting service identity (for example `spring-cloud-gateway` or `tcp-proxy-service`) and must be retained by recording rules, alerts, and dashboards.
      - `scope` is canonical bounded scope, using `scope="environment"` before gameplay scope exists, not a raw unbounded identifier.
      - `path` is a bounded enum (for example `telnet` or `websocket`).
      - `outcome` is the shared bounded eligibility/outcome classification: `success`, `server_failure`, `user_rejection`, `policy_rejection`, or `unknown`. Malformed protocol traffic is `user_rejection`; intentional abuse/rate-limit and other policy denials are `policy_rejection`. Detailed bounded reason belongs in a separate diagnostic dimension or protected evidence.
    - The SLI should be computed as `sum by (service, scope, path) (rate(entrypath_connection_attempts_total{outcome="success"}[...])) / sum by (service, scope, path) (rate(entrypath_connection_attempts_total{outcome=~"success|server_failure"}[...]))`.
    - Auxiliary meters such as `tcpproxy_connections_limit_exceeded_total` remain useful drilldowns but are not sufficient to define availability by themselves.
    - Profiles claiming independent monitoring must also run **independent synthetic probes** from outside the gameplay ingress boundary and retain the external monitor's evidence for a low-cardinality result such as `entrypath_blackbox_probe_success{path,target}`. `path` is the bounded enum `websocket` or `telnet`; `target` is the bounded logical enum `gateway` or `tcp_proxy`, never a host, URL, deployment, or runtime identifier. These external probes are the authoritative detection source for total entry-path outages that prevent traffic from ever reaching Gateway or TCP Proxy (for example LB, DNS, TLS, or ingress policy failures), while `entrypath_connection_attempts_total` remains the authoritative in-service breakdown for `outcome` analysis once traffic reaches the edge. Profiles that omit independent monitoring retain their explicit degraded/operator-dependent detection posture and do not claim this external evidence.
  - Alert routing:
    - Entry-path alerts should preserve `service` from the emitting series and include `component="entrypath"` so Telnet-path and WebSocket-path incidents can route and page independently.
  - Detection model:
    - Treat the 1-day availability window as a calibration/compliance view until the profile has an enforced measured objective.
    - Publish short- and long-window error-budget burn views for an enforced objective so acute and sustained failures are distinguished without treating one noisy or statistically insufficient window as a breach.
    - In the target state, profiles claiming independent monitoring install blackbox probe alerts alongside the in-service SLI alerts once the deployment-owned applicability and expected-series gate identifies the complete exposed path set, so “no requests reached the service” is still detected as a P0 edge-path outage; until that gate exists, the checked-in profile manifests leave these alerts uninstalled. Profiles that omit independent monitoring retain the explicit degraded/operator-dependent posture instead of claiming this external alert authority.
- **Chat delivery latency**
  - SLI: time from chat message submission to delivery to all intended recipients, ending at the canonical `recipient_dispatch` boundary. The ADR 0160 player-experience chat SLI uses `completion_boundary="recipient_dispatch"` as its sole canonical completion boundary, with `chat_delivery_latency_ms` keyed by canonical emitting `service`, approved bounded `scope`, `completion_boundary`, and chat `channel_type`.
  - Calibration starting point: 99% of eligible chat messages are delivered in < 1s over a 5-minute window for active regions.
  - Instrumentation: emitted by the chat/social service responsible for delivering chat events, with labels for canonical `service`, approved bounded `scope`, `completion_boundary`, and `channel_type`, plus an explicit distinction between player-visible channels (global, zone, party) and system channels. `completion_boundary` remains a finite diagnostic enum: `server_acceptance`, `recipient_dispatch`, `transport_write`, or `client_acknowledgement`, corresponding to durable server acceptance, dispatch to every intended server-side recipient, transport write, or client acknowledgement. `server_acceptance`, `transport_write`, and `client_acknowledgement` are separately named diagnostic metrics/objectives only; they must not be aggregated into, substituted for, or used to satisfy the ADR 0160 player-experience chat SLI. Measurements from different completion boundaries or emitting services must not be compared, combined, or aggregated into one SLI; only `recipient_dispatch` is eligible for the canonical chat SLI.

Environment and service docs that introduce new player-facing flows should:

- Reuse these SLIs where possible (for example by tagging `command_end_to_end_latency_ms` with a new `command` label), or
- Add new SLIs to this section so that operators have a single, authoritative list of player-centric targets.

Enforced profiles use short and long windows together so alerts represent material error-budget burn rather than one noisy ratio. Missing producers, absent series, and too few eligible observations remain explicit `unknown` evidence. The checked-in rules and dashboards currently encode initial numeric values before cohesive producers and representative calibration exist; they are templates and implementation drift, not proof that FireMUD has adopted measured hard gates.

Grafana dashboards under `design/observability/grafana` include:

- `player-experience.json` – canonical player-SLI calibration dashboard; it is informational until a deployment profile promotes a measured objective to enforcement.
- `player-experience-drilldown.json` – calibration/incident drilldown dashboard for per-outcome and per-command/channel investigation; it does not by itself establish an SLO breach.

### Player Experience Metrics Catalog (Target-State Contract)

The metrics below are the desired Prometheus-facing shapes for player experience SLIs/SLOs under the bounded `scope` contract. They are not the current implemented service metric set. Services may emit additional bounded drilldown metrics, but dashboards and alerts should prefer these names once producers exist and their labels have been reconciled with the metrics-cardinality policy:

- Login:
  - `login_requests_total{service,scope,outcome}` where `service` is the canonical ingress emitter, the pre-gameplay `scope` baseline is `environment`, and `outcome` uses the shared bounded eligibility/outcome classification: `success`, `server_failure`, `user_rejection`, `policy_rejection`, or `unknown`. Invalid credentials and malformed submissions are `user_rejection`; abuse/rate-limit and other intentional denials are `policy_rejection`; platform errors and timeouts are `server_failure`.
- Commands:
  - `command_end_to_end_latency_ms_bucket{service,scope,command,le}` with bounded `service`, `scope` drawn from the metric family's documented bounded values, and `command` drawn from a documented, bounded command set for core SLO coverage.
    - Starting bounded set for SLO coverage (normalize synonyms/aliases in instrumentation): `move`, `look`, `combat`.
    - Alert rules and dashboards may filter to this bounded set (for example `command=~"move|look|combat"`) while retaining `service` so the SLO signal stays stable and service-specific even when new commands are introduced.
  - `command_latency_stage_ms_bucket{service,scope,command,stage,le}` for bounded stage-level drilldown. Required `stage` values are `edge_queue`, `dispatch`, `tick_wait`, and `domain_commit`; environment overlays may add a small number of additional bounded stages only with a design update here.
- Entry-path availability:
  - `entrypath_connection_attempts_total{service,scope,path,outcome}` with the bounded emitting `service`, pre-gameplay `scope` baseline `environment`, and bounded enums for `path` and the shared eligibility/outcome classification described above. Recording rules, alerts, and dashboards must retain `service` rather than aggregating it away.
- Synthetic player-flow canaries:
  - For profiles advertising player-flow canaries, `playerflow_canary_success{flow,path,target,profile}` is the mirrored result of the most recent synthetic login or representative-command run, for example `playerflow_canary_success{flow="login",path="websocket",target="gateway",profile="independent-required"}`.
  - For profiles advertising player-flow canaries, `playerflow_canary_latency_ms{flow,path,target,profile}` is the mirrored latency of the same synthetic run in milliseconds, for example `playerflow_canary_latency_ms{flow="command",path="telnet",target="tcp_proxy",profile="independent-required"}`.
  - `playerflow_canary_last_run_timestamp_seconds{flow,path,target,profile}` is the Unix timestamp of that flow/path's most recent attempted run. A fresh failed run retains its timestamp and emits `playerflow_canary_success` with `value=0`; a stopped runner eventually makes the timestamp stale instead of leaving a prior success green.
  - The profile's authoritative `externalAuthority.detectionBudgetSeconds` is the freshness budget for advertised player-flow canaries; it must not be replaced by a universal hard-coded interval. An advertised canary always retains and publishes the complete canary mirror family, including `playerflow_canary_freshness_budget_seconds{profile}`, independently of the separate external-monitoring `prometheusMirrors` capability, because the target-state canary alerts evaluate that budget once their applicability gate exists. Profiles that do not advertise the capability omit all canary metrics. For `independent-omitted`, the profile budget is canary timing only and does not establish external-monitoring authority.
- Chat:
  - `chat_delivery_latency_ms_bucket{service,scope,completion_boundary,channel_type,le}` with `service` derived from the canonical emitting service identity, `scope` drawn from the metric family's documented bounded values, `completion_boundary` drawn from the finite diagnostic enum `{server_acceptance,recipient_dispatch,transport_write,client_acknowledgement}`, and `channel_type` drawn from a bounded enum (global/zone/party/system, etc.). The ADR 0160 player-experience chat SLI and its recording rules and alerts must select only `completion_boundary="recipient_dispatch"`; `server_acceptance`, `transport_write`, and `client_acknowledgement` remain separately named diagnostic metrics/objectives and cannot be aggregated into or satisfy that SLI. Dashboards may retain `service` and all completion-boundary values for diagnostic breakdowns.

### Synthetic Player-Flow Canaries (Target-State Profile-Advertised Contract)

Live-traffic SLIs remain the authoritative compliance view for player experience. Profiles that advertise continuous player-experience monitoring must also run **independent synthetic canaries** for the most critical player flows on every path in their declared `exposedPublicPlayerPaths` set; profiles that do not advertise this capability may omit these canary metrics. A non-exposed path is `not_applicable`, not a canary failure, and an advertised canary whose evidence is missing, stale, or unavailable is `unknown`/degraded rather than a green or failed result. This capability is distinct from the ADR 0159 independent-monitoring claim: a profile requiring independent monitoring still retains deadman and real public-path evidence even when it omits player-flow canaries.

- Required flows:
  - `flow="login"` for an end-to-end login through each public entry path.
  - `flow="command"` for one bounded representative gameplay command after login. The starting canonical command is `command="look"`.
- Required paths:
  - `path="websocket"` for the browser/Gateway path when that path is in `exposedPublicPlayerPaths`; otherwise record `not_applicable`.
  - `path="telnet"` for the TCP Proxy path when that path is in `exposedPublicPlayerPaths`; otherwise record `not_applicable`.
- Metric mirror contract:
  - `playerflow_canary_success{flow,path,target,profile}` – boolean-like result for the most recent synthetic run as mirrored into Prometheus; `target="gateway"` is used for the WebSocket/Gateway path and `target="tcp_proxy"` for the Telnet/TCP Proxy path.
  - `playerflow_canary_latency_ms{flow,path,target,profile}` – latency of the synthetic run in milliseconds, mirrored into Prometheus as a gauge or histogram-derived recording, using the same target enum.
  - `playerflow_canary_last_run_timestamp_seconds{flow,path,target,profile}` – Unix timestamp of the most recent attempted run for each required flow on each actually exposed path. The timestamp is fresh for both passing and failing attempts.
  - `playerflow_canary_freshness_budget_seconds{profile}` – Prometheus mirror of the authoritative profile's configured `externalAuthority.detectionBudgetSeconds`; this bounded gauge is required with the complete canary mirror family whenever the player-flow canary is advertised, regardless of the separate external-monitoring `prometheusMirrors` capability. It is absent when the canary capability is omitted.
- Cardinality constraints:
  - `flow`, `path`, and `profile` are bounded enums; `profile` is the deployment's declared external-monitoring profile and must match the profile-specific freshness budget.
  - `target` is a bounded logical enum, currently `{gateway, tcp_proxy}`; it identifies the canonical ingress surface, never a host, URL, deployment/runtime ID, tenant, or player identity.
  - Do not label these metrics with canary account IDs, character IDs, tenant IDs, or trace IDs.
- Operational contract:
  - Each monitored transport uses a separate restricted synthetic character and isolated, deterministic canary data so overlapping WebSocket and Telnet probes cannot trigger one-session takeover against each other.
  - Advertised execution is single-flight per deployment/profile/path journey: at most one run may hold the verifier-owned run lease for a given exposed path at a time. Each run has a unique protected run identity and verifier-bound identity/data-set revision; its login and command evidence must remain bound to that run and may not borrow credentials, characters, fixtures, timestamps, or results from another run. A concurrent same-path invocation is skipped or deferred and recorded as runner-health evidence; it must not update the path's canary result. Different exposed paths may run concurrently because they use separate restricted transport characters and independently bound evidence. If the authoritative verifier cannot enforce this single-flight/identity binding, the advertised capability remains omitted.
  - Synthetic identities are marked authoritatively and remain subject to authentication, abuse protection, authorization, moderation, security monitoring, and durable audit. Synthetic status is never a security bypass.
  - Validated canary traffic is excluded only from product analytics, ordinary player-behavior interpretation, and live-player SLO denominators.
  - Provisioning, least-privilege access, credential delivery, rotation and revocation, deterministic reset, and retirement are supported lifecycle operations; production credentials are never seeded defaults.
  - These canaries are an outage-detection path, not the primary SLO compliance metric. They complement, but do not replace, `login_requests_total` and `command_end_to_end_latency_ms_bucket`.
  - Advertised execution follows the declared monitoring profile and must alert independently from live-traffic volume. An `independent-omitted` profile may retain a local canary timing contract, but it does not claim external deadman, public-path, or off-cluster paging authority.

The single-flight rule is a target verifier/lifecycle obligation, not current runner proof. The checked-in runner has no continuous scheduler or Account-owned synthetic identity/isolation verifier and therefore downgrades requested advertised canaries to `omitted`.

#### Canary Alert Contract (Target-State Profile-Advertised Contract)

To keep advertised synthetic canaries actionable instead of merely visible, profiles that advertise this capability must install a canonical alert set for canary failures:

- Required alert families:
  - `PlayerFlowCanaryLoginFailed`
  - `PlayerFlowCanaryCommandFailed`
  - `PlayerFlowCanaryLatencyHigh`
  - `PlayerFlowCanaryEvidenceStale`
  - `PlayerFlowCanaryFreshnessBudgetMissing`
  - `PlayerFlowCanaryEvidenceMissing`
- Label and routing requirements:
  - `owner="platform"` for login and entry-path availability of the synthetic path.
  - `owner="gameplay"` for representative command success/latency once the canary is authenticated and in-session.
  - `service` should preserve the runtime entry-path emitter identity where the alert expression is service-specific. For the canonical canary expressions, which intentionally span all exposed paths, do not hardcode a service; use `component="playerflow-canary"` and preserve the failing `path`, `target`, and `profile` labels in the alert instance (for example, with `path="{{ $labels.path }}"`, `target="{{ $labels.target }}"`, and `profile="{{ $labels.profile }}"`).
  - `PlayerFlowCanaryEvidenceStale` is evaluated by Prometheus rather than one entry-path emitter and may use `service="prometheus"` for platform routing while preserving its bounded `flow`, `path`, `target`, and `profile` labels.
  - `runbook` must point to the player-experience or incident response runbook section that explains how to validate whether the canary reflects a real outage versus canary-only breakage.
- Severity requirements:
  - One failed sample or one broken synthetic identity is not immediately P0. A sustained, fresh, confirmed complete-login failure may use `severity="P0"` even without live traffic; isolated, stale, credential, fixture, or runner failures use the appropriate monitoring-degradation severity.
  - `PlayerFlowCanaryCommandFailed` should use `severity="P1"` because it indicates in-session gameplay degradation on a monitored public path, but does not by itself prove a total entry outage.
  - `PlayerFlowCanaryLatencyHigh` should use `severity="P1"` because it indicates sustained player-visible degradation on a monitored public path without requiring a total failure.
  - `PlayerFlowCanaryEvidenceStale` should use `severity="P1"` because it indicates that advertised canary evidence is degraded and cannot establish current player-flow health.
  - `PlayerFlowCanaryFreshnessBudgetMissing` should use `severity="P1"` because canary result series without their profile budget cannot safely drive the failure, latency, or stale-evidence alerts.
  - `PlayerFlowCanaryEvidenceMissing` should use `severity="P1"` because an advertised canary has not produced its first run and cannot establish current player-flow health.
- The canonical Prometheus canary alerts use a maximum `for: 2m` hold. Every profile advertising player-flow canaries must declare a positive `detectionBudgetSeconds` of at least 180 seconds: the two-minute longest hold plus a 60-second evaluation margin. The validator rejects a smaller authoritative budget rather than allowing an alert hold to outlive fresh evidence.
- Detection requirements:
  - Success/failure alerts use the declared cadence and a sustained confirmation window suitable for outage detection rather than paging on one sample, and they do not rely on live-traffic volume. They are installed and evaluated only for advertised canary capability and exposed paths; omitted capabilities and non-exposed paths are `not_applicable`, while missing or stale advertised evidence is `unknown`/degraded. A failure alert may use a `value=0` or high-latency result only while its matching `playerflow_canary_last_run_timestamp_seconds{...,profile}` is within the same profile's freshness budget; an expired or absent timestamp must never leave a prior success green or turn stale evidence into a failure.
  - Latency alerts must evaluate `playerflow_canary_latency_ms{flow,path,target,profile}` using millisecond thresholds and preserve the bounded `flow`, `path`, `target`, and `profile` labels, gated by the matching fresh run timestamp and the same-profile `playerflow_canary_freshness_budget_seconds{profile}`.
  - `PlayerFlowCanaryEvidenceStale` must preserve the bounded `flow`, `path`, `target`, and `profile` labels and fire when an available run timestamp exceeds the same profile's freshness budget. A present `playerflow_canary_success` result whose matching last-run timestamp is missing is also owned by this stale-evidence condition and remains unknown/degraded; it must not be treated as a canary failure.
  - `PlayerFlowCanaryFreshnessBudgetMissing` must preserve `profile` and fire when canary result series exist for that profile without a matching `playerflow_canary_freshness_budget_seconds{profile}` series. It remains quiet when the canary capability is omitted.
  - `PlayerFlowCanaryEvidenceMissing` must use a deployment-owned expected-series inventory for every advertised flow/path/target/profile tuple and fire when a wholly absent expected tuple has no matching `playerflow_canary_last_run_timestamp_seconds` series after the profile's first-run grace period. This expected-series/first-run gate is required for `independent-omitted` profiles as well as `independent-required` profiles; it does not depend on an external deadman and remains quiet when the canary capability is omitted or a path is not exposed. The inventory and monitor remain an implementation gap; the checked-in stale rule does not claim to provide them.
  - Controlled non-production failure injection for each required canary path must be testable without paging production destinations.
- Relationship to live-traffic SLIs:
  - Canary alerts complement, but do not replace, the live-traffic SLO alerts for `login_requests_total` and `command_end_to_end_latency_ms_bucket`.

### Degraded Modes and Observability Dependencies

Moderation and admin workflows should remain usable even when parts of the observability stack are degraded. To avoid coupling core actions to non‑authoritative systems:

- **Hard dependencies:** Logging & Admin Service itself, the domain services that own authoritative game data (for example, Game Session Service, Entity Management Service, Account Service), and Spring Cloud Gateway are required for core moderation and admin actions such as inspecting live sessions, muting, banning, or kicking players, or updating feature flags. Feature-flag writes remain unavailable until the complete [ADR 0048](./decisions/adr-0048-durable-idempotent-operator-write-execution.md) owner-write gate is implemented and proved, including durable intent/tuple state, Account reference redemption at the owner, owner idempotency and fencing, durable result evidence, and read-only recovery/reconciliation.
- **Soft dependencies:** Elasticsearch, Prometheus, Jaeger, Grafana, Kibana, and Alertmanager are treated as **best‑effort enrichments**. When any of these backends are unavailable or degraded, the Logging & Admin UI should:
  - Clearly indicate which data sources are unavailable (for example, “logs currently unavailable”, “metrics degraded”, or “traces unavailable”).
  - Continue to expose core moderation and admin APIs based on authoritative game data wherever possible.
  - Hide or disable only those features that require the missing backend (for example, embedded dashboards or historical trace searches), rather than failing the entire moderation workflow.
- **Alert routing:** When Alertmanager is unavailable, Logging & Admin reports that routed-alert state is unavailable. It may display fresh, bounded Prometheus diagnostic values, but it does not reconstruct pending alerts or create a second active-alert authority; stale diagnostics become unknown.
  - For broader “observability stack outage” scenarios (Prometheus down, Elasticsearch down, Jaeger down), follow `design/architecture/system-architecture-observability-incident-runbook.md` for bounded diagnostic workflows and recovery verification.

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
  - **P0** – Player-visible outage or severe SLO breach for core flows (for example login unavailable, command latency outside SLO for a large fraction of players, or sustained Redis tail-loss SLO breach affecting active gameplay; exact region impact comes from authoritative runtime-health/control-plane lookup).
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
- PostgreSQL backup/restore freshness, artifact lineage/readability, and recovery-convergence alerts: `owner="infra"`. Tick-pause alerts remain maintenance/reset signals owned according to the affected control plane, not routine backup health.
- Tick behavior and gameplay-flow correctness alerts (for example replay storms, ledger backlogs, unsafe tick runtime ratios): `owner="gameplay"`.
- Observability stack availability/routing alerts (Prometheus, Alertmanager, Elasticsearch, Jaeger, Grafana): `owner="platform"`.

Player SLO owner mapping (normative):

- Login success ratio alerts (`LoginSuccessRatioLowGateway`, `LoginSuccessRatioLowTcpProxy`): `owner="platform"` (ingress/auth availability domain).
- Entry-path availability alerts (`EntryPathAvailabilityLowGateway`, `EntryPathAvailabilityLowTcpProxy`): `owner="platform"` (edge connectivity domain).
- Command latency alerts (`CommandLatencyP99HighGateway`, `CommandLatencyP99HighTcpProxy`): `owner="gameplay"` (in-session runtime performance domain).
- Chat delivery latency alerts (`ChatDeliveryLatencyP99High`): `owner="gameplay"` (player-facing runtime behavior domain).

### Alert-State Degradation Without a Second Authority

Alertmanager owns current alert-routing state while healthy; it is not game, moderation, recovery, or safety authority. Logging & Admin may render that routed state with its observation timestamp and canonical ownership/runbook labels.

When Alertmanager is unavailable, Logging & Admin displays an explicit `alert routing unavailable` state. If Prometheus remains reachable, it may also show a bounded diagnostic snapshot of selected canonical recording-rule values. That snapshot is labelled diagnostic and time-bound; it is never merged into a second authoritative active-alert list and requires no duplicate-suppression or alert-family equivalence engine.

Diagnostic values include `observed_at` and freshness. After the configured budget, five minutes by default, their state becomes `unknown`; stale values must not appear current. If Prometheus is also unavailable, the UI reports observability state unavailable and relies on the independent deadman where the profile provides one; omitted profiles retain their explicit degraded-detection warning.

Operational and safety actions use authoritative domain and control-plane state such as command status, tick ownership, moderation records, admission controls, and recovery records. Required audit records for operator or moderation mutations are durable domain evidence and are not best-effort observability. Elasticsearch indexing, metrics, dashboards, traces, and alert delivery remain enrichments rather than commit dependencies for those actions.

### Observability Stack Alerts

Observability backends are best-effort enrichments for gameplay and moderation workflows, but they still require first-class alerts because their failure can mask player-visible incidents and break operator triage.

- Hosted production profiles that claim externally verified availability or monitoring-resilient readiness must provide an **independent monitoring path** outside the Prometheus + Alertmanager failure domain. It pages on deadman freshness and probes every real public gameplay path declared in `exposedPublicPlayerPaths`; non-exposed paths are `not_applicable`.
- Hobby, single-node, and small profiles may explicitly omit that external path. Preflight records and warns about the weaker detection posture without blocking traffic; those profiles cannot claim independent outage detection, externally verified public-path availability, off-cluster paging, or monitoring-resilient readiness.
- The authoritative external path and any Prometheus-facing metric mirror are separate contracts. The external pager is the source of truth during total in-cluster observability outages; Prometheus may mirror the same state for dashboards and runbooks when healthy. See [`design/observability/external-monitoring/README.md`](../observability/external-monitoring/README.md).
- Each selected deployment profile installs the canonical `platform`-owned alert families for the observability capabilities it advertises and can prove. A selected profile records every omitted backend family as `not_applicable`; it must not install or claim alerts for an omitted capability. The default indexed profile includes:
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
- For profiles that require independent monitoring, the in-cluster alert set above complements but does not replace the off-cluster path for total observability-stack outages.

#### External Probe and Deadman Contract (Normative)

Deployment profiles must declare the complete bounded `exposedPublicPlayerPaths` set (`websocket` and/or `telnet`) and one value from ADR 0159's canonical monitoring-profile enum: `independent-required` or `independent-omitted`. The path set also scopes any advertised player-flow canary capability. Hosted production profiles that claim externally verified availability use `independent-required`; hobby, single-node, and small profiles may use `independent-omitted` with a non-blocking preflight warning and an explicit degraded-detection status. These serialized values are not shortened to `required` or `omitted` in evidence, metric labels, or rules.

For an `independent-required` profile, the independent monitor:

- runs outside the monitored cluster and its Prometheus + Alertmanager failure domain;
- evaluates the freshness of the canonical in-cluster heartbeat;
- exercises every real public gameplay path in `exposedPublicPlayerPaths`; and
- pages through an off-cluster notification authority.

Each required profile records an explicit result for every path: an exposed path must have current real-public probe evidence, while a non-exposed path is `not_applicable`. An exposed path omitted from the configuration or evidence invalidates the independent public-path claim. The default heartbeat interval is 60 seconds and the default stale threshold is 180 seconds. Each profile records its actual heartbeat interval, stale threshold, probe cadence, evaluation window, and maximum detection budget; the defaults may be changed only with matching evidence and operational claims. The local Prometheus deadman rule is installed only when the profile declares `prometheusMirrors=published` (or a documented equivalent mirror contract), and consumes the profile-aware `observability_deadman_stale{profile="independent-required"}` mirror after those configured values have been applied; it must not recreate universal `180` second or `2m` timing. Without that mirror capability the local deadman rule is `not_applicable`, while the authoritative external monitor and pager remain required and authoritative. Public-path blackbox zero and absent-mirror alert rules are target-only until a deployment-owned expected-series configuration gates them to the complete exposed path set. Such an absent-mirror condition is applicable only when `prometheusMirrors=published` (or a documented equivalent mirror contract) and the expected series is advertised; otherwise a non-exposed path is `not_applicable`. Prometheus, Alertmanager, Grafana, Kibana, Jaeger, and collector interfaces may remain private. Provider-native or in-cluster checks may diagnose those components, but external reachability of every observability UI is not part of this contract.

To keep optional mirrors, dashboards, and runbooks aligned:

- **Authoritative external monitor**
  - Must page using its own native checks and thresholds even when Prometheus is fully unavailable.
  - Must not depend on Prometheus alert evaluation to turn external probes or deadman freshness into pages.
- **Prometheus mirror**
  - May ingest or mirror the external-monitor state using the metric names below so dashboards, Prometheus rules, and smoke tests can refer to a stable vocabulary.
  - The mirrored metrics are not sufficient by themselves to satisfy the independent detection requirement.

- `entrypath_blackbox_probe_success{path,target}`:
  - May mirror the external result for each public player entry path.
  - `path` is a bounded enum and must use `websocket` for the browser/Gateway path and `telnet` for the TCP Proxy path.
  - `target` is a bounded logical enum: `gateway` for the WebSocket/Gateway path or `tcp_proxy` for the Telnet/TCP Proxy path. It must never contain a host, URL, deployment, runtime ID, tenant, or player identity.
  - Values are boolean-like: `1` when the synthetic probe can complete the target handshake and `0` when it cannot.
  - Canonical alerts and dashboards may aggregate across `target`, but must preserve `path`.
- `observability_deadman_heartbeat_timestamp_seconds{source}`:
  - May mirror the independently hosted deadman/meta-monitoring result.
  - `source` is a bounded environment or heartbeat-observed label, not the exact external monitor identity; retain the exact monitoring product/deployment identity in external evidence.
  - The signal records the latest successful heartbeat time as observed by the independent monitor, not by Prometheus itself.
  - Deadman paging should trigger when this timestamp becomes stale according to the environment's configured heartbeat budget.

- `observability_deadman_stale{profile}`:
  - Optional boolean-like mirror of the external monitor's profile-aware stale decision. The external monitor applies the profile's configured stale threshold and evaluation window before publishing `1`.
  - Prometheus deadman rules gate on `profile="independent-required"`; an `independent-omitted` profile does not produce a P0 from an absent heartbeat or synthesize green external authority.
  - This signal carries the configured timing semantics across the Prometheus boundary; rules must not replace them with an unconditional `180` second threshold or `2m` evaluation window.

The Prometheus mirror is optional convenience telemetry, not proof that the independent monitor or pager works. Required profiles retain current off-cluster evidence for heartbeat evaluation, every exposed public probe, explicit `not_applicable` results for non-exposed paths, monitor health, and page delivery. Expired evidence becomes `unknown`. Omitted profiles expose their common-failure-domain or operator-dependent posture instead of synthesizing green external evidence.

### Log Pipeline Queryability Contract

Structured log emission is not sufficient by itself. A profile claiming indexed-log observability proves the asynchronous emitter-to-query path so operators can actually investigate incidents:

- Scope:
  - Gateway, Game Session, TCP Proxy, and any other service that owns a player-facing or tick-critical path.
- Required behavior:
  - An asynchronous check emits a uniquely identifiable structured record with a known `traceId`, then polls the supported operator query path until that exact record is retrievable through the collector or forwarder and selected storage backend.
  - `firemud-logs-*` and Elasticsearch/Kibana are the default indexed profile; another backend may document an equivalent field, delay, access, and degraded-state mapping.
  - The default starting target is queryability within 2 minutes of emission. Each profile records its configured delay and observed result; the value is not an immutable cross-platform constant.
  - Operators must be able to retrieve the smoke records by `service` and `traceId`, and by `tenantId` / `gameInstanceId` / `regionId` / `characterId` when those fields are expected by the logging contract.
  - The canonical new-record emit-and-query check is conditional on the profile's emitter/query automation being implemented and contract-proven. Until then, the current fallback records capability `log-queryability-omitted` with result `not_applicable` and an explicit omission reason; for profiles that require indexed queryability, the applicable claim or gate remains closed. It must not synthesize evidence, report green, or block unrelated process, player-readiness, or traffic claims.
  - Where the exact script-transition composition/readback capability is advertised, a focused smoke/readback must capture fresh pre-transition and post-transition reads from the authoritative current-pin `GetPinnedScriptPatchVersion`, the Game Session-owned non-authoritative `GetGameSessionPinConvergence`, and Automation's observed `GetAutomationPinConvergence`, and emit/retrieve structured audit/log/trace records retaining both exact `scriptPatchVersion` and `scriptPinEpoch`. Before the transition, the fresh authoritative current-pin read establishes the previous exact tuple or explicit semantic `UNPINNED` state; the Game Session mutation/history evidence must retain that same previous state, while the convergence observations are baseline evidence and cannot be mistaken for target convergence. After the transition, the authoritative current pin and both convergence observations must agree on the requested target exact tuple and applicable `controlPlaneRequestId`; both `currentConvergenceAttemptGeneration` (Game Session) and `observedConvergenceAttemptGeneration` (Automation) must also match the authoritative workflow's current attempt generation. A recovered attempt must reuse the same request identity and target tuple while exposing its new authoritative attempt generation; a prior attempt generation cannot satisfy readback. Automation's projection must also report `isProjectionStale=false` with `projectionLagMs` within `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS`. When the smoke exercises a same-version repin, it must prove the epoch change while the patch value remains the same. If current implementation status says the advertised composition/readback capability is absent, the check reports canonical `UNAVAILABLE` for the missing tuple/readback capability and does not claim tuple proof. When this exact capability is required for a prod-like promotion, release, or indexed-observability assurance claim, canonical `UNAVAILABLE` is non-passing and keeps only that applicable gate or claim closed; it is not a degraded pass and never participates in process liveness, Kubernetes pod or gameplay readiness, or player-traffic admission. That capability-unavailable result is distinct from a smoke transport, dependency, or execution failure, which is reported as the corresponding failure and likewise does not claim tuple proof; neither absence nor failure is treated as a stale/mismatched tuple. These raw values remain structured fields and are not ordinary high-cardinality metric labels.
- Failure semantics:
  - Missing, expired, or failing hosted evidence blocks the applicable promotion or release and indexed-observability readiness claim. It never fails process liveness, Kubernetes pod or gameplay readiness, or player-traffic admission.
  - Runtime backend loss is explicit degraded observability. Gameplay, moderation, and domain operations do not synchronously query or wait for the log backend.
  - The synthetic query identity is read-only and scoped to the environment and required log stream; proof by `traceId` must not create broad cross-tenant search authority.
  - Hobby, single-node, and small profiles may prove console or journal retrieval or explicitly declare indexed search unavailable without blocking player traffic or synthesizing hosted evidence.

If an environment cannot emit these exact metric names because of a hosted monitoring product constraint, it must provide a documented compatibility mapping to equivalent mirrored signals and keep the canonical alert names, `path` semantics, authoritative external paging behavior, and runbook behavior unchanged. Logging & Admin, runbooks, and smoke tests should treat the canonical names above as the default contract.

For scripting and automation workloads, dashboards and alerts must include both live and dry-run activity:

- Live triggers and automation work are reported via metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total`, as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md` and `design/architecture/system-architecture-scripting-observability-contract.md`.
- Dry-run and test executions are tracked separately via `automation_script_test_runs_total` and `automation_script_test_runtime_seconds` so operators can see when validation tools are consuming significant sandbox resources even though they bypass mainline ScriptQuota and tenant automation budgets.
- Do not label metrics with high-cardinality identifiers such as `scriptEventId`; use logs/traces and `script_event_audit` queries for per-event correlation.

## Health Checks

- Spring Boot `/actuator/health/readiness` and `/actuator/health/liveness` endpoints feed Kubernetes readiness and liveness probes.
- See [Deployment Environments](./infrastructure/deployment-environments.md#kubernetes-health-monitoring) for probe behavior.

## Error Tracking and Hotfixes

For the default indexed target profile, operators may use Kibana to search for uncaught exceptions or repeated crashes; compatible profiles use their mapped query path, and reduced profiles use only their declared console/journal path or explicit indexed-search omission. Profiles that install Prometheus/Alertmanager may alert on high error rates. These are selected-profile operational paths, not evidence that the current Logging & Admin service embeds or proxies them. When issues arise, operators follow the runbooks to build and deploy a hotfix image from an immutable reviewed source revision (for example, a commit SHA or signed release tag), never a moving branch tip. After the incident, the fix is returned to the active release line through the normal reviewed change flow before the hotfix is retired.

## Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
- [Redis Operations & Migrations](./system-architecture-redis-operations.md)
- [System Architecture Overview](./system-architecture-overview.md)
