# FireMUD System Architecture: Tracing

This document explains how distributed traces are collected and visualized across FireMUD services.

## Implementation Notes

The current implementation exports generic gRPC server spans through the shared `TracingInterceptor` and tags application-level gRPC errors on the active span. The repository does not yet prove cross-service context propagation, named workflow spans, configurable service sampling, or collector tail sampling. The named gameplay, tick, TCP Proxy, and backup spans below are target vocabulary, not universally shipped behavior. The manual shared SDK configuration does not yet consume `OTEL_TRACES_SAMPLER` or `OTEL_TRACES_SAMPLER_ARG`, so their presence alone does not establish service-scoped incident-sampling capability.

Operational tracing claims are capability-gated:

1. **Baseline observability** relies on metrics and structured logs; generic RPC spans and correlation are best-effort unless proved for the environment.
2. **Workflow tracing** covers only named workflows whose semantic spans, bounded attributes, context propagation, ingestion, and queries have end-to-end proof.
3. **Service-scoped incident sampling** additionally requires a wired sampler control and a proved increase/observe/revert drill.
4. **Tenant/game-instance/region-scoped incident sampling** additionally requires candidate delivery from every upstream service participating in the scoped workflow, full propagation of the scope attributes across that workflow, bounded collector tail sampling, and safe time-limited enable/revert proof.

Each environment must advertise its proved level and covered workflows. Runbooks must branch on that declaration and must not make mitigation depend on traces.

---

## OpenTelemetry Collector

All services emit spans using the OpenTelemetry SDK. A dedicated **OpenTelemetry Collector** runs inside the Kubernetes cluster to receive OTLP traffic and forward it to storage backends. A sample manifest is provided at `k8s/monitoring/otel-collector.yaml`.

- Deploy using the official [`opentelemetry-collector`](https://github.com/open-telemetry/opentelemetry-helm-charts) Helm chart or apply the sample manifest for local demos.
- The collector runs as the `otel-collector` service inside the cluster so other pods can reach it via `http://otel-collector:4317`.
- The collector exposes a `4317` gRPC endpoint. Services export spans to `http://otel-collector:4317` by default (see `.env.sample`). Override the address with the `OTEL_ENDPOINT` environment variable (`otel.endpoint` property). See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability) for details.

  ```bash
  OTEL_ENDPOINT=http://collector.internal:4317
  ```

- The collector forwards spans to Jaeger over gRPC port `14250`.
- Metrics about the collector itself are scraped by Prometheus from `/metrics`
  on port `8888`.
- The repository provides example Kubernetes collector and Jaeger manifests. A local or hosted environment must deploy and verify its own supported tracing path; the current Docker Compose file does not include those services.

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
- Retention is finite and profile-specific according to the sensitivity, incident need, storage budget, and privacy/export/erasure policy. FireMUD makes no universal 30-day production promise.
- Access the UI locally with:

  ```bash
  kubectl port-forward service/jaeger 16686:16686
  ```

## Span Catalog and Conventions

To make traces consistently useful across services and runbooks, FireMUD uses a small, shared span vocabulary and attribute set. New instrumentation should reuse these names and attributes rather than introducing ad hoc patterns.

- **Gateway and command path**
  - Gateway spans:
    - `gateway_request` – inbound HTTP and WebSocket and Telnet-bridged request into Spring Cloud Gateway or the TCP Proxy Service, tagged with `route`, `method`, `tenantId`, and, where applicable, `characterId`.
    - `gateway_command_dispatch` – dispatch from Gateway/Proxy into Game Session or other domain services, tagged with `command`, `tenantId`, `gameInstanceId`, `regionId`, and `characterId`.
  - Game Session and domain spans:
    - `gamesession_handle_command` – top-level span for handling a gameplay command, tagged with `command`, `tenantId`, `gameInstanceId`, `regionId`, and `characterId`.
    - Domain-specific spans such as `entity_apply_damage`, `inventory_transfer`, `room_resolve_look`, and `quest_update_state`, tagged with `tenantId`, `gameInstanceId`, `regionId`, and any relevant aggregate identifiers.
- **Tick executor and coordination**
  - `tick_schedule` – scheduling of ticks for a `<tenantId, gameInstanceId, regionId>`, tagged with `tenantId`, `gameInstanceId`, `regionId`, `tickId`, and `regionEpoch`.
  - `tick_execute` – execution of a single tick, tagged with `tenantId`, `gameInstanceId`, `regionId`, `tickId`, `regionEpoch`, and a `tick_phase` attribute for major phases (for example `load_effects`, `apply_effects`, `persist_ledger`, `drain_followups`).
  - `tick_apply_effect` – per-effect spans for calls into domain services, tagged with `tenantId`, `gameInstanceId`, `regionId`, `tickId`, `effectKey`, `effect_type`, and `targetAggregateType`.
- **Telnet/TCP Proxy and WebSocket bridge**
  - `tcpproxy_connection` – lifecycle of a Telnet connection at the DMZ edge, tagged with `remote_ip_hash` (and optionally `remote_ip_prefix`), `tenantId`, and high-level `connection_outcome` using the shared bounded values `success`, `server_failure`, `user_rejection`, `policy_rejection`, or `unknown`. Specific causes remain diagnostic context in a separately documented bounded reason dimension or protected logs; they are not free-form span values.
  - `tcpproxy_command` – command forwarding from Telnet to Gateway, tagged with `command`, `tenantId`, and `characterId`.
  - `tcpproxy_notify_disconnect` – spans for `NotifyDisconnect` calls into Game Session, tagged with `tenantId`, `characterId`, and `disconnect_reason`.
- **Cross-region and saga flows**
  - `gamesession_remote_followup_enqueue` – span for enqueuing cross-region follow-ups, tagged with origin and target `gameInstanceId`/`regionId`, `tenantId`, and a coarse `followup_type`.
  - `gamesession_remote_followup_drain` – span for draining remote follow-ups in the target region, tagged similarly and correlated with tick execution spans.
- **Backup and recovery flows**
  - `backup_pg_dump_snapshot` – span measuring the online transactionally consistent logical backup, tagged with environment/database identity and immutable artifact lineage rather than gameplay scope.
  - `backup_verify_artifact` – span for integrity and restore-readability verification, tagged with artifact and backup/restore-tool identities.
  - `recovery_converge_participant` – span for one declared recovery participant's safe disposition, tagged with bounded participant type and outcome.
  - Tick pause/resume spans belong to maintenance, reset, migration, and future scoped-recovery traces. Routine backup does not emit or require them.

Named span families may include only the attributes their documented incident queries require. Availability in request context is not permission to copy an attribute onto every span. Where allowlisted:

- `tenantId`, `gameInstanceId`, `regionId`, and `characterId` may support tenant/runtime investigation on the named span families that require them. The canonical deployment environment resource attribute is `deployment.environment.name` (for example `production` or `development`); do not introduce a trace-local alias such as `trace_locale`.
- Error attributes such as `error.code` and `error.type` drawn from the same bounded catalogs used by `grpc.app_error` and domain error handling.

The `command` attribute is always a normalized bounded verb/type, never the raw command line. Free-form application error messages are not span attributes.

## Sampling and Sensitive Attributes

- **Sampling**
  - A deployment may explicitly disable tracing and rely on metrics, structured logs, health, durable audit, and authoritative owner state. It advertises no workflow-tracing or sampling-escalation capability.
  - An environment at a sampling-capable level declares and proves its baseline, covered workflows, root-trace ratio, span/byte budget, and evidence. Approximately 1% may be a calibration seed for high-volume entry paths, not a universal minimum or correctness boundary.
  - Incident mode may be promised only at a proved service-scoped or tenant/game-instance/region-scoped level. Every escalation is time- and volume-bounded, audited, has an automatic revert deadline, and verifies return to the declared baseline.
  - Runbooks must treat traces as a best-effort diagnostic: when sampling is too low to find a representative trace, operators should pivot to metrics (SLO/SLI panels) and logs (Kibana searches filtered by `tenantId`, `gameInstanceId`, `regionId`, and `traceId` when available).
  - Absence of a sampled trace is absence of trace evidence, not proof that an event did not occur. Traces never gate mitigation, reset, recovery, or an authoritative domain decision.
- **Sensitive attributes**
  - Exact gameplay identifiers such as `characterId` are operationally useful but sensitive. They appear only on allowlisted named span families and require least-privilege environment-scoped query access, query auditing, finite retention, and declared export/privacy/erasure handling.
  - Raw client IP addresses are forbidden in all traces. Where stable network correlation is justified, `remote_ip_hash` is a rotating environment-specific keyed HMAC with documented custody and correlation window. It remains pseudonymous network data.
  - `remote_ip_prefix` (`/24` for IPv4 or `/56` for IPv6) is disabled by default. A profile may enable it only for justified abuse investigation with short retention and equivalent access/audit controls.
  - Do not attach user-provided text, chat, raw commands, descriptions, payloads, free-form error or exception messages, secrets, or credentials as span attributes. Use bounded codes and types; separately protected logs may carry redacted detail under their own policy.
  - External/shared exporters and backends require equivalent producer/collector filtering, encryption, environment isolation, query authorization/audit, retention, export, and deletion controls.

See [ADR 0167](./decisions/adr-0167-allowlisted-sensitive-trace-attributes.md).

### Incident-Mode Sampling Procedure (Design Contract)

FireMUD defines two optional target escalation levels for incident-mode sampling. An operator may use only a level advertised and proved by that environment, must choose the least invasive sufficient option, and must record scope, incident identity, start time, automatic expiry, volume budget, completion, and verified reversion.

1. **Service-scoped sampling (fast, coarse)**
   - Mechanism: adjust head sampling in the affected service(s) via standard OpenTelemetry env vars:
     - `OTEL_TRACES_SAMPLER=parentbased_traceidratio`
     - `OTEL_TRACES_SAMPLER_ARG=<ratio>` (for example `0.10` for 10%)
   - Operational shape:
     - Through the environment's authorized deployment-control owner, roll out a temporary configuration change to the affected Deployment(s). Persist the affected service/workflow scope, incident identity, start time, positive TTL/lease, volume budget, and expiry with the change; the owner or a durable expiry/reconciliation controller must automatically roll it back at expiry even if the initiating operator disappears.
     - Verify: in Jaeger, `service.name="<service>"` should show a visibly higher trace volume within a few minutes.
     - Revert: at the expiry deadline, a durable expiry/reconciliation path must retry removal and apply a safe deployment reload/rollback, including when the incident workflow is abandoned. If that operation fails, the expired elevated configuration must not be retained as terminal “last valid” state: keep completion blocked, continue durable reconciliation, and use an emergency disable-to-baseline path when available, with its own safe reload/rollback and verification. Mark the escalation complete only after sampled volume returns to the measured pre-escalation baseline; until then retain an explicit incomplete/degraded state.
   - Limits: this cannot scope sampling to a specific `tenantId`, `gameInstanceId`, or `regionId`; it increases volume for the service overall.

2. **Tenant/game-instance/region-scoped sampling (precise, requires collector support)**
   - Mechanism: configure the OpenTelemetry Collector to apply tail-sampling policies based on span attributes such as `tenantId`, `gameInstanceId`, and `regionId` (as defined in this document’s span catalog).
   - Operational shape:
     - Add a temporary “always sample” policy for the target `<tenantId, gameInstanceId, regionId>` (and optionally `service.name`) through the environment's authorized collector-control owner. Persist the incident scope, owner identity, start time, positive TTL/lease, volume budget, and expiry with the policy; the owner or a durable expiry controller must remove it automatically when the TTL/lease expires, including if the initiating operator disappears. A free-form time-bound note alone is not enforceable support.
     - Verify: in Jaeger, filtering by `tenantId`/`gameInstanceId`/`regionId` should yield traces even when baseline sampling is low.
     - Revert: at the declared deadline, a durable expiry/reconciliation path must retry removal of the temporary policy and apply and validate a safe collector reload. If reload or rollback fails, the expired elevated policy must not be retained as terminal “last valid” state: keep completion blocked, continue durable reconciliation, and use an emergency disable-to-baseline path when available, with safe reload/rollback and verification. Mark incident sampling complete only after sampled volume returns to the measured pre-escalation baseline; until then retain an explicit incomplete/degraded state.
   - Limits: this requires tail sampling, candidate delivery from every upstream service participating in the scoped workflow, and full propagation of the scope attributes across the workflow. Tail sampling cannot recover a trace already discarded by service-side head sampling.

Declared sampler controls and their current support status are documented in `design/architecture/infrastructure/environment-and-secrets-catalog.md#observability`.

#### Collector Capability Contract (For Scoped Incident Sampling)

Environments that claim support for tenant/game-instance/region-scoped incident sampling (staging and production-like) must satisfy all of the following:

- OpenTelemetry Collector is deployed with tail-sampling processors enabled.
- Tail-sampling policies can match on `tenantId`, `gameInstanceId`, and `regionId` span attributes, and optionally `service.name`.
- Every upstream service that can create spans for the scoped workflow delivers candidate traces to the collector for the incident window; service-side head sampling must not discard those candidates before tail sampling.
- Every relevant span in the scoped workflow carries the complete `<tenantId, gameInstanceId, regionId>` scope, and service-to-service propagation preserves all three attributes on downstream spans rather than relying on the entry span alone.
- Collector config supports safe runtime update/reload for temporary incident policies.
- Temporary policy changes are owned by an authorized control-plane identity and carry an enforceable positive TTL/lease; a durable expiry/reconciliation path removes expired policies even after the initiating request or operator is gone.
- Runbook-level verification exists:
  - Positive check: candidate traces from each participating upstream service, including downstream spans with the complete scoped `<tenantId, gameInstanceId, regionId>`, appear above baseline after policy enablement.
  - Negative check: the collector accepts the removal/reload safely and trace volume returns to the measured pre-escalation baseline before support is marked complete.

If an environment does not meet this contract, it must advertise its highest proved lower level—service-scoped sampling or baseline observability—and incident procedures must not claim tenant/game-instance/region-scoped escalation there.

## Operational Playbook: Using Traces During Incidents

During incidents, Jaeger is a first-class tool alongside logs and metrics only for workflows included in the environment's proved tracing level. Otherwise use the corresponding metrics and structured-log path. The following are target queries for environments that advertise the required workflow spans:

- **Stuck or degraded tick region**
  - Filter by `operation= "tick_execute"` (or the equivalent span name) and `tenantId`/`gameInstanceId`/`regionId`.
  - Look for long-running spans or repeated spans for the same `tickId` and `regionEpoch`.
  - Drill into child spans (`tick_apply_effect`, domain spans) to identify slow downstream services or guard failures.
- **Replay storms and idempotency issues**
  - Filter by `operation = "tick_apply_effect"` and `effectKey` or `effect_type` for the hot effect categories.
  - Verify how often the same effect identity appears in a short time window and correlate with `tick_effect_outcome_total` metrics.
- **Telnet/TCP Proxy incidents**
  - Filter by `service.name = "tcp-proxy-service"` and spans such as `tcpproxy_connection` or `tcpproxy_notify_disconnect`.
  - Correlate high `tcpproxy.telnet.discarded` and `tcpproxy.disconnect.notify.transport_failure` metrics with specific traces to understand whether failures are due to abusive clients, PROXY header issues, or downstream Game Session behavior.
- **Backup and recovery issues**
  - Search for `backup_pg_dump_snapshot` and `backup_verify_artifact` around the expected schedule and confirm their artifact lineage matches.
  - For drills or restores, inspect `recovery_converge_participant` outcomes, including the participant entries retained for controlled reopen; do not infer routine backup failure from the absence of tick-pause spans.

Runbooks for Redis incidents, tick failures, scaling decisions, and backup/recovery reference these span names and query patterns so operators have concrete examples to follow rather than starting from scratch in Jaeger.

## Related Documentation

- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Microservice Template](./microservices/service-template.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [System Context Diagram](./system-context-diagram.md)
- [Tick Incident Runbook](./system-architecture-tick-incident-runbook.md)
