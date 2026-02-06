# FireMUD System Architecture: Tracing

This document explains how distributed traces are collected and visualized across FireMUD services.

---

## OpenTelemetry Collector

All services emit spans using the [OpenTelemetry](https://opentelemetry.io/) SDK. A dedicated **OpenTelemetry Collector** runs inside the Kubernetes cluster to receive OTLP traffic and forward it to storage backends. A sample manifest is provided at `k8s/monitoring/otel-collector.yaml`.

- Deploy using the official [`opentelemetry-collector`](https://github.com/open-telemetry/opentelemetry-helm-charts) Helm chart or apply the sample manifest for local demos.
- The collector runs as the `otel-collector` service inside the cluster so other pods can reach it via `http://otel-collector:4317`.
- The collector exposes a `4317` gRPC endpoint. Services export spans to `http://otel-collector:4317` by default (see `.env.sample`). Override the address with the `OTEL_ENDPOINT` environment variable (`otel.endpoint` property). See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability) for details.

  ```bash
  OTEL_ENDPOINT=http://collector.internal:4317
  ```

- The collector forwards spans to Jaeger over gRPC port `14250`.
- Metrics about the collector itself are scraped by Prometheus from `/metrics`
  on port `8888`.
- The local Docker Compose stack includes the collector and Jaeger so developers can inspect traces locally.

Every service relies on a shared `TracingConfig` in the `common-library` (`services/common-library/src/main/java/net/firedevops/firemud/common/config/TracingConfig.java`). This
configuration sets the `service.name` resource from `spring.application.name`,
uses a `BatchSpanProcessor`, and sends spans to the collector. The
`LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` from the
[Shared Libraries](./system-architecture-shared-libraries.md) are registered in
each service's gRPC configuration (`GrpcConfig` or `GrpcServerConfig`) so
requests are instrumented with logs, metrics, and spans consistently. See
[Logging & Monitoring](./system-architecture-logging-monitoring.md) for
additional observability details. `TracingInterceptor` opens a span for each
gRPC method and marks it successful or cancelled when the call completes.

## Jaeger UI

Traces are stored and visualized with **Jaeger**. A minimal Jaeger deployment is provided in `k8s/monitoring/jaeger.yaml`.

- Jaeger receives OTLP data from the collector.
- The web UI is exposed on port `16686` within the cluster.
- Retention settings are environment specific; development keeps a few days of data, while production retains up to 30 days.
- Access the UI locally with:

  ```bash
  kubectl port-forward service/jaeger 16686:16686
  ```

## Span Catalog and Conventions

To make traces consistently useful across services and runbooks, FireMUD uses a small, shared span vocabulary and attribute set. New instrumentation should reuse these names and attributes rather than introducing ad hoc patterns.

- **Gateway and command path**
  - Gateway spans:
    - `gateway_request` – inbound HTTP/WebSocket/Telnet-bridged request into Spring Cloud Gateway or the TCP Proxy Service, tagged with `route`, `method`, `tenantId`, and, where applicable, `playerId`.
    - `gateway_command_dispatch` – dispatch from Gateway/Proxy into Game Session or other domain services, tagged with `command`, `tenantId`, `regionId`, and `playerId`.
  - Game Session and domain spans:
    - `gamesession_handle_command` – top-level span for handling a gameplay command, tagged with `command`, `tenantId`, `regionId`, `playerId`, and `instanceId`.
    - Domain-specific spans such as `entity_apply_damage`, `inventory_transfer`, `room_resolve_look`, and `quest_update_state`, tagged with `tenantId`, `regionId`, and any relevant aggregate identifiers.
- **Tick executor and coordination**
  - `tick_schedule` – scheduling of ticks for a `<tenantId, regionId>`, tagged with `tenantId`, `regionId`, `tickId`, and `region_epoch`.
  - `tick_execute` – execution of a single tick, tagged with `tenantId`, `regionId`, `tickId`, `region_epoch`, and a `tick_phase` attribute for major phases (for example `load_effects`, `apply_effects`, `persist_ledger`, `drain_followups`).
  - `tick_apply_effect` – per-effect spans for calls into domain services, tagged with `tenantId`, `regionId`, `tickId`, `effectKey`, `effect_type`, and `targetAggregateType`.
- **Telnet/TCP Proxy and WebSocket bridge**
  - `tcpproxy_connection` – lifecycle of a Telnet connection at the DMZ edge, tagged with `remote_ip`, `tenantId`, and high-level `connection_outcome` (for example `ok`, `limit_exceeded`, `malformed`).
  - `tcpproxy_command` – command forwarding from Telnet to Gateway, tagged with `command`, `tenantId`, and `playerId`.
  - `tcpproxy_notify_disconnect` – spans for `NotifyDisconnect` calls into Game Session, tagged with `tenantId`, `playerId`, and `disconnect_reason`.
- **Cross-region and saga flows**
  - `gamesession_remote_followup_enqueue` – span for enqueuing cross-region follow-ups, tagged with origin and target `regionId`, `tenantId`, and a coarse `followup_type`.
  - `gamesession_remote_followup_drain` – span for draining remote follow-ups in the target region, tagged similarly and correlated with tick execution spans.
- **Backup and pause/resume flows**
  - `backup_pause_ticks` – span for pausing ticks before `pg_dump`, tagged with `tenantId` scope (for example `all` or specific tenants) and a `reason`.
  - `backup_pg_dump_snapshot` – span measuring the logical backup operation itself.
  - `backup_resume_ticks` – span for resuming ticks after the snapshot, tagged consistently with `backup_pause_ticks`.

All spans should include, where applicable:

- `tenantId`, `regionId`, `playerId`, and `trace_locale` (for example `prod-us-east-1` or `dev-local`) so traces can be filtered by tenant and environment.
- Error attributes such as `error.code` and `error.type` drawn from the same bounded catalogs used by `grpc.app_error` and domain error handling.

## Sampling and Sensitive Attributes

- **Sampling**
  - Production-like environments should assume sampling is enabled and that not every request/tick will produce a trace.
  - Runbooks must treat traces as a best-effort diagnostic: when sampling is too low to find a representative trace, operators should pivot to metrics (SLO/SLI panels) and logs (Kibana searches filtered by `tenantId`, `regionId`, and `traceId` when available).
  - If a workflow requires trace availability as part of an operational contract (for example debugging a recurring tick stall), document the minimum sampling expectations for that workflow explicitly in the owning runbook.
- **Sensitive attributes**
  - Attributes such as `playerId` are operationally useful but should be treated as sensitive data and kept bounded (IDs only, no message payloads).
  - Do not attach user-provided text (chat content, command payloads, free-form error messages) as span attributes; keep that data in logs with appropriate redaction and retention controls.
  - When exporting traces outside of the cluster or into shared tooling, ensure access controls and retention policies match the sensitivity of these identifiers.

## Operational Playbook: Using Traces During Incidents

During incidents, Jaeger is a first-class tool alongside logs and metrics. The following queries and patterns are used by runbooks:

- **Stuck or degraded tick region**
  - Filter by `operation= "tick_execute"` (or the equivalent span name) and `tenantId`/`regionId`.
  - Look for long-running spans or repeated spans for the same `tickId` and `region_epoch`.
  - Drill into child spans (`tick_apply_effect`, domain spans) to identify slow downstream services or guard failures.
- **Replay storms and idempotency issues**
  - Filter by `operation = "tick_apply_effect"` and `effectKey` or `effect_type` for the hot effect categories.
  - Verify how often the same effect identity appears in a short time window and correlate with `tick_effect_outcome_total` metrics.
- **Telnet/TCP Proxy incidents**
  - Filter by `service.name = "tcp-proxy-service"` and spans such as `tcpproxy_connection` or `tcpproxy_notify_disconnect`.
  - Correlate high `tcpproxy.telnet.discarded` and `tcpproxy.disconnect.notify.failure` metrics with specific traces to understand whether failures are due to abusive clients, PROXY header issues, or downstream Game Session behavior.
- **Backup and pause/resume issues**
  - Search for `backup_pause_ticks` and `backup_resume_ticks` spans around the time of a backup.
  - Confirm that `backup_pg_dump_snapshot` spans align with the expected backup schedule and that pauses are short-lived relative to SLOs.

Runbooks for Redis incidents, tick failures, scaling decisions, and backup/recovery reference these span names and query patterns so operators have concrete examples to follow rather than starting from scratch in Jaeger.

## Related Documentation

- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Microservice Template](./microservices/service-template.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [System Context Diagram](./system-context-diagram.md)
- [Tick Incident Runbook](./system-architecture-tick-incident-runbook.md)
