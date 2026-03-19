# FireMUD System Architecture: Testing Strategy

FireMUD employs a layered testing approach to keep services reliable while avoiding excessive CI/CD costs. This document describes the scope of each test type, the tooling in use, and how these tests fit into our development workflow.

Environment terminology in this document follows [Deployment Environments](./infrastructure/deployment-environments.md#canonical-environment-classes). Unless a section says otherwise, “prod-like observability smoke” means environment classes `hobby-self-hosted`, `staging`, and `production`; `dev-demo-cluster` may run a subset for rehearsal, but it is not the authoritative environment for prod-like observability sign-off.
For `hobby-self-hosted`, equivalent operator-run evidence is acceptable when nightly automation is not practical, but the same contracts still apply: external-authority checks, mirrored signals, and player-flow canaries must be validated before player traffic is opened or reopened.
Minimum acceptable operator-run evidence for that exception is: one retained preflight or smoke record for the event, one retained check result showing the authoritative external pager/deadman path was exercised or verified, one retained check result showing mirrored `entrypath_blackbox_probe_success` / `observability_deadman_heartbeat_timestamp_seconds` and required `playerflow_canary_*` signals were present, and one retained incident or deployment note that records who performed the verification and when.
Unless a more specific runbook defines another canonical evidence path for the same event, store that retained evidence under `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json` for first-live and reopen events, or alongside the matching recovery record under `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json` when the verification is part of restore hardening.
For first-live and reopen events, the illustrative `traffic-open-record/v1` shape in `design/architecture/system-architecture-backup-recovery.md#hobby-traffic-open-evidence` is the canonical example artifact for this retained evidence.
That artifact should minimally preserve: the event identity (`deploymentRef` or `recoveryRef`), the operator identity and timestamp, the backing preflight or smoke evidence reference, and the retained results for the authoritative external pager/deadman verification plus mirrored `entrypath_blackbox_probe_success` / `observability_deadman_heartbeat_timestamp_seconds` / `playerflow_canary_*` checks.

Illustrative retained evidence shape for a prod-like observability smoke or hobby traffic-open event:

```json
{
  "deploymentRef": "staging-2026-03-19-a",
  "verifiedAt": "2026-03-19T10:55:00Z",
  "verifiedBy": "operator@example",
  "preflightEvidenceRef": "ci://observability-smoke/2026-03-19T10:40:00Z",
  "externalAuthority": {
    "deadmanIncidentOpened": true,
    "entrypointChecks": {
      "prometheus": "green",
      "alertmanager": "green",
      "grafana": "green",
      "kibana_log_query": "green",
      "jaeger_query": "green"
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "staging-web-gateway", "value": 1},
      {"path": "telnet", "target": "staging-telnet-edge", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917600
    },
    "playerflow_canary_success": [
      {"flow": "login", "path": "websocket", "target": "staging-web-gateway", "value": 1},
      {"flow": "command", "path": "websocket", "target": "staging-web-gateway", "value": 1}
    ]
  },
  "canaryAlerts": [
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P0", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryCommandFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryLatencyHigh", "severity": "P1", "exerciseResult": "passed"}
  ],
  "logPipelineQueryability": {
    "traceId": "9c8d7e6f5a4b3210",
    "queryPath": "firemud-logs-*",
    "queryableWithinSeconds": 74,
    "verifiedFields": ["service", "traceId", "tenantId", "regionId", "playerId"]
  }
}
```

This example is illustrative rather than exhaustive. Equivalent retained evidence is acceptable as long as it preserves the same canonical checks and operator accountability.

---

## Testing Scope

Each microservice has its own unit and integration tests. Cross‑service scenarios are also covered in a dedicated suite. Load tests run independently using Gatling in a separate `load-testing` module. The cross‑service directories contain example tests that can be expanded as needed.

- **Unit tests** live under each service in `src/test/java/unit/`.
- **Integration tests** for that service live in `src/test/java/integration/` and may start Redis, Postgres, or other dependencies on demand.
- **Cross-service integration tests** exercise workflows that span multiple services. They live under `src/test/java/crossservice/` in each service and start companion containers with Testcontainers. Docker images for the cooperating services must be built (for example via `./gradlew buildDockerImages`) or pulled from GHCR. A unified `crossServiceTest` Gradle task runs them collectively, or run `./gradlew :service-name:test --tests "*CrossServiceIntegrationTest"`.
- Many of these tests are annotated with `@Testcontainers(disabledWithoutDocker = true)` so they are skipped when Docker is unavailable.
- **Load tests** reside in `dev-tools/load-testing/src/gatling` and simulate real usage patterns. Run them with `./gradlew :load-testing:gatlingRun`. Full high-concurrency load tests are typically run on demand; CI may run a small smoke-load profile to catch obvious regressions without blocking deployments.

Test data seeding strategies use the `dev-tools/seed/seed-test-data.sh` script to populate a minimal world for local testing, and an automated approach seeds data for integration tests.

### Redis in Tests

Redis participates in several layers of the test strategy:

- **Unit tests** do not talk to Redis directly; any Redis interactions are mocked or exercised via small, in-memory fakes.
- **Service-level integration tests** may start a single coordination and cache Redis pair using Testcontainers:
  - Coordination tests use the same prefixes and Lua scripts as production (`tick:*`, `session:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`), but run against a disposable coordination instance whose state is reset between tests or suites.
  - Cache/rate-limit tests use a distinct container or logical database for `inventory:*`, `view:room-look:*`, and `ratelimit:*` prefixes so eviction behaviour can be validated independently.
- **Cross-service integration tests** (for example, Look or Login vertical slices) bring up Redis alongside multiple services and exercise canonical flows:
  - Testcontainers typically start a `redis-coord` and `redis-cache` pair, mirroring the role separation from `docker-compose` and Helm.
  - Tests treat coordination state as reset-tolerant within the suite: they rely on the tick replay and session recovery rules described in [System Architecture: Redis](./system-architecture-redis.md), but do not assume persistence across independent test runs.

In all of these test layers, coordination Redis behaves like the **“single-node without AOF (ephemeral coordination)”** profile from [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md):

- Coordination Redis instances used by tests are disposable and fully reset-tolerant; they do **not** validate tail-loss SLOs, AOF replay guarantees, or the long-running coordination buffer semantics described for persistent environments. Durable effect history and idempotency behavior are exercised primarily via PostgreSQL-ledger and domain-state checks, not by asserting properties of Redis AOF files.
- Cache/Rate-Limit Redis in tests mirrors the production role separation (dedicated cache instance) but is likewise treated as ephemeral and safe to reset between suites.
- Staging and production environments remain responsible for validating AOF behavior, tail-loss envelopes, and reset runbooks; tests focus on correctness of flows under idealized, fresh coordination state rather than persistence characteristics.

When adding new Redis-dependent tests:

- Prefer existing helper builders and key helpers from `firemud-common` so prefixes and hash-tag rules stay consistent with production.
- Avoid hard-coding `localhost`/ports; instead, wire tests through the same `FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*` style configs that production uses, with values supplied by Testcontainers.
- Do not introduce ad-hoc `FLUSHDB`/`FLUSHALL` calls against shared development Redis instances; test setups should isolate data in per-test containers or use tenant-specific prefixes and explicit cleanup.

---

## Tooling and Gradle Layout

- **JUnit & Mockito** provide the core framework for unit and integration tests.
- **Spring Test** bootstraps service contexts and external resources.
- **Gatling** drives load testing scenarios.

The repository uses a hierarchical Gradle setup. The root `build.gradle.kts` delegates to per‑service builds. Each service exposes standard `test`, `integrationTest`, and cross‑service tasks. Example commands:

```bash
./gradlew :service-name:test
./gradlew :service-name:integrationTest
./gradlew crossServiceTest
```

Unit and integration tests run automatically in GitHub Actions through a matrix of `:service-name:check` tasks. Cross-service tests run via the `crossServiceTest` Gradle task.

## Cross-Service Integration Testing

For workflows that span multiple services, such as account creation and world provisioning, the suite starts several containers at once using **Testcontainers**. Each container joins a shared network so gRPC calls function just like in production.

### Example Workflow

1. Launch PostgreSQL and Redis containers.
2. Start Account, Game Session, and World Management services.
3. Perform a registration and login sequence to verify saga state.

```kotlin
val network = Network.newNetwork()
val postgres = PostgreSQLContainer<Nothing>("postgres:16").withNetwork(network)
val accountService = GenericContainer("account-service:latest").withNetwork(network)
```

This example uses a shared Testcontainers `Network` for cross-service orchestration.

These tests validate saga orchestration logic, and the `crossServiceTest` Gradle task runs them.

---

## CI/CD Integration

GitHub Actions executes formatting and lint checks, builds the code, and runs all unit and integration tests via `:service-name:check` for each module. Coverage reports are generated and a Trivy security scan is executed. See the [CI/CD Pipeline](./system-architecture-cicd.md) document for the workflow definition.

Full high-concurrency load testing is executed on demand outside of CI and does not block deployments. CI may run a small smoke-load profile to catch regressions, but it should not be treated as a substitute for deliberate performance testing.

### High-Concurrency Load Testing

Gatling scenarios simulate thousands of concurrent connections to measure service limits and uncover bottlenecks. Results guide scaling decisions and database indexing.

### Security Testing

OWASP ZAP crawls the web client and Gateway endpoints during CI to surface common web vulnerabilities. Penetration tests and rate-limiting checks run before major releases.

### Observability Tests

In addition to functional, load, and security tests, FireMUD treats observability wiring as part of the system contract. A minimal set of checks should validate that critical metrics and alerts are present and correctly labeled:

- **Metric presence and labels**
  - After a small synthetic workload in CI (for example a short end-to-end smoke test that exercises login and a few commands), assert that:
    - `grpc_app_error_total` metrics are exported with bounded `code` labels taken from the shared error catalog and a stable `service` label derived from `spring.application.name`.
    - At least one tick-related metric such as `tick_execution_time_ms_bucket` or `tick_execution_time_ms_p95` appears for a synthetic region in environments where ticks run.
    - Where player command SLO instrumentation is enabled, `command_latency_stage_ms_bucket` appears with the bounded `stage` enum from the Logging & Monitoring contract (`edge_queue`, `dispatch`, `tick_wait`, `domain_commit`) so latency triage does not silently regress to “traces only”.
    - Where dynamic tail-loss or pause-budget rules are enabled, `tick_interval_ms` is exposed for that synthetic region so cadence-derived recordings can evaluate consistently.
    - `tick_effect_outcome_total` is emitted for at least one synthetic tick effect, with `outcome` values limited to the documented set (for example `first_apply`, `replay_ok`, `guard_error`).
    - If replay-controller instrumentation is present, `tick_effects_replay_scan_lag_ms` and `tick_effects_replay_batches_total` appear for the synthetic region.
    - Where Redis coordination is enabled, a basic tail-loss or coordination metric such as `redis_coordination_tail_loss_ms` is exposed, even if its value is near zero in CI.
    - Where coordinated backups are enabled, backup pause metrics expose both observed values and budget gauges (`backup_tick_pause_wait_seconds`, `backup_tick_pause_wait_budget_seconds`, `backup_tick_pause_duration_seconds`, `backup_tick_pause_duration_budget_seconds`).
    - Where alias-scope pause/resume is still supported, `backup_pause_scope_alias_requests_total` is exported.
  - These checks should confirm that metrics follow the cardinality guardrails defined in the Logging & Monitoring doc (for example, no `traceId` or `playerId` labels).
- **Alert wiring smoke tests**
  - Define one or more **test-only** alert rules (for example `ObservabilitySmokeTestAlert`) in non-production Alertmanager configurations with `alert_class="test"` and notifications routed only to low-noise channels or logging sinks, not to paging integrations.
  - Provide a short-lived probe in CI that intentionally pushes the corresponding test-only metric over its threshold in a non-production environment and verifies that Alertmanager receives and routes the alert with the expected labels (`service`, `severity="P2"`, `alert_class="test"`, `owner`, `runbook`).
  - These smoke tests can run as non-blocking or informational checks initially; once stable, they can be promoted to required checks for production-like environments, but they must never reuse P0/P1 production alert rules or target production Alertmanager instances directly.
  - In prod-like observability smoke, also verify that the independently hosted deadman / meta-monitoring path is receiving the in-cluster heartbeat signal. This check must not depend on Prometheus being healthy to succeed.
  - In prod-like observability smoke, also verify the **authoritative external pager** path itself:
    - force a deadman-staleness test target or equivalent external-only failure mode,
    - verify the external monitoring product opens the expected non-production incident without depending on Prometheus rule evaluation,
    - verify the mirrored Prometheus signal matches the external monitor state once Prometheus is healthy again.
  - In prod-like observability smoke, also verify the canonical Logging & Admin alert-state behavior:
    - when Alertmanager is healthy, the UI/API reports `source="alertmanager"` (or an equivalent explicit source marker) and does not duplicate the same condition from fallback rules,
    - when Alertmanager is intentionally made unavailable while Prometheus remains healthy, the UI/API reports `source="prometheus-fallback"` for the supported fallback set and marks the view as degraded,
    - when both Alertmanager and Prometheus are unavailable, the UI/API reports alert-state unavailability instead of presenting stale fallback conditions as current.

- **Tracing checks**
  - In at least one non-production pipeline where Jaeger (or an OTLP-compatible trace backend) is available, run a small smoke test that:
    - Exercises a login flow and a representative gameplay command.
    - Verifies the presence of at least one `gamesession_handle_command` span with attributes such as `tenantId`, `regionId`, and `playerId`.
    - Verifies the presence of at least one `tick_execute` span in environments where ticks are enabled.
    - Verifies the presence of at least one TCP edge incident span (`tcpproxy_notify_disconnect` or `tcpproxy_connection`) in environments that expose the Telnet path.
    - Verifies the presence of at least one backup coordination span (`backup_pause_ticks` and `backup_resume_ticks`) in environments that run coordinated backup workflows.
    - For coordinated backup workflows, verifies the required backup-scope attributes on those spans:
      - `scope_type` is present.
      - `tenantId` is present when the request is tenant- or region-scoped.
      - `regionId` is present for canonical region-scoped requests.
      - `alias_scope_used=true` is present when the workflow intentionally exercises legacy `game_instance_id` alias scope.
  - These checks may be skipped in environments without tracing backends but should be treated as required in pipelines that advertise tracing support, so span regressions are caught before production.

- **Structured log-field contract checks**
  - After a short synthetic login + command + tick smoke flow, assert that representative log lines from Gateway, Game Session, and TCP Proxy contain the structured fields required by the logging contract:
    - Required for request/tick handling paths: `service`, `traceId`, `correlationId`.
    - Required when known in context: `tenantId`, `regionId`.
    - Required when a player session is authenticated/bound: `playerId`.
  - Fail the check if any expected service path emits only free-form messages without these fields, because incident runbooks and Kibana drilldowns depend on those keys.
  - In prod-like observability smoke, also verify end-to-end log pipeline queryability:
    - run a synthetic login + command + tick flow that records expected `traceId` values,
    - verify the resulting records arrive in the canonical Elasticsearch/Kibana log-query path within the environment's bounded indexing delay (default starting point: within 2 minutes unless the environment documents a stricter bound),
    - verify those records are retrievable by `service` and `traceId`, plus `tenantId` / `regionId` / `playerId` when applicable,
    - fail readiness if structured logs are emitted but not queryable end-to-end through the documented log-query path.

New services and features that add critical metrics or alerts should extend these observability tests where feasible so configuration errors are caught in CI rather than only in staging or production.

### Synthetic Player-Flow Canary Checks

Prod-like environments that advertise player-experience monitoring must also validate the synthetic canary path described in the Logging & Monitoring contract:

- Verify `playerflow_canary_success{flow="login",path=...}` exists for each exposed public path.
- Verify `playerflow_canary_success{flow="command",path=...}` exists for each exposed public path.
- Verify `playerflow_canary_latency_ms{flow="command",path=...}` is exported with millisecond semantics.
- Verify canary labels remain low-cardinality (`flow`, `path`, `target`) and do not include account IDs, tenant IDs, player IDs, or trace IDs.
- Verify the canonical canary alert path can be exercised in non-production without using production paging destinations:
  - login canary failure trips `PlayerFlowCanaryLoginFailed` with `severity="P0"` (or the documented environment-equivalent canonical alert),
  - representative command failure trips `PlayerFlowCanaryCommandFailed` with `severity="P1"`,
  - controlled latency degradation trips `PlayerFlowCanaryLatencyHigh` with `severity="P1"`,
  - alert labels preserve `owner`, `severity`, and `runbook` from the architecture contract.
- These checks are required for prod-like observability smoke because live-traffic SLIs alone are not sufficient in low-traffic periods.

#### Where These Checks Run (Decision)

To keep PR feedback fast while still preventing “it only breaks in staging” drift, FireMUD uses a two-tier expectation:

- **Always (PR + main CI)**:
  - Design-contract validation of dashboard/snippet consistency (for example `dev-tools/observability/validate-observability-contract.py`).
  - Markdown link + lint checks so runbook references do not rot.
- **Prod-like observability smoke (nightly or staging-gated)**:
  - Alert routing smoke: trigger a test-only alert (`alert_class="test"`, `severity="P2"`) and verify Alertmanager routing and label preservation end-to-end.
  - External-authority smoke: verify the independently hosted monitoring system can page on deadman staleness or an equivalent external-only failure target without relying on Prometheus alert evaluation.
  - External observability-entrypoint smoke: verify the authoritative external monitoring configuration covers Prometheus, Alertmanager, Grafana, Kibana/log-query, and Jaeger/trace-query entrypoints, and that each has a documented non-production validation method or bounded mirrored signal mapping.
  - External edge blackbox smoke: verify prod-like environments expose an independent synthetic probe metric for each public entry path and that a forced probe failure (or equivalent test target) trips the non-production blackbox alert path.
  - Player-flow canary smoke: verify the prod-like environment exposes mirrored `playerflow_canary_success` and `playerflow_canary_latency_ms` signals for login and the representative command path, and that a controlled non-production failure can trip the canary alert path.
  - Tracing smoke: run a login + representative command flow and verify at least one `gamesession_handle_command` span (and one `tick_execute` span where ticks run) is present in the trace backend. In environments that expose Telnet and coordinated backups, also verify at least one `tcpproxy_notify_disconnect`/`tcpproxy_connection` span and one `backup_pause_ticks` + `backup_resume_ticks` pair.
  - Structured log contract smoke: verify sampled logs from critical paths contain required structured fields (`service`, `traceId`, `correlationId`, plus contextual `tenantId`/`regionId`/`playerId`).
  - Log pipeline queryability smoke: verify those same synthetic records are queryable end-to-end in the canonical Elasticsearch/Kibana or documented compatible log-query path.
  - Prometheus rules conformance smoke: query the Prometheus rules API and verify the required fallback/recording rules are loaded (tail-loss fallback, tick safety ratio recording, login success ratio recording, command p99 latency recording, entry-path availability recording, and chat delivery latency recording).
    - This includes the canonical dynamic tail-loss pair (`redis_coordination_tail_loss_budget_ms`, `redis_coordination_tail_loss_slo_breached`) and both short-window and 1-day entry-path availability recordings.
    - This includes preserving the bounded `command` label on the core-command latency recording rules so single-command regressions continue to alert.
    - This includes the replay-convergence set (`tick_effects_pending_oldest_age_seconds`, `tick_effects_replay_convergence_budget_seconds`, `tick_effects_replay_slo_breached`, and `tick_effects_replay_starved`) so ledger backlog alerting does not drift into environment-specific guesswork.
    - This also includes backup fallback signals (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_tick_pause_wait_budget_breached`, `backup_tick_pause_duration_budget_breached`, `backup_ticks_paused_budget_breached`) and the observability alert group (`firemud.alerts.observability`) so new platform-health alerts cannot drift out of the shared ruleset silently.
  - External-signal contract smoke: verify the prod-like environment exposes the canonical independent-signal contract from `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative`, or a documented compatibility mapping:
    - `entrypath_blackbox_probe_success{path,target}` for `path="websocket"` and `path="telnet"`, or a documented equivalent mapping.
    - `observability_deadman_heartbeat_timestamp_seconds{source}` or a documented equivalent external heartbeat signal.
    - `playerflow_canary_success{flow,path,target}` and `playerflow_canary_latency_ms{flow,path,target}` for the required login and representative command flows, or a documented equivalent mapping.
    - For the deadman path, verify the configured staleness threshold matches the architecture contract (`3 * heartbeat_interval_seconds`).

This split ensures that contract drift is caught on every change, while backend-dependent checks run only where Alertmanager/Jaeger are actually available.

---

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Testing & Continuous Delivery](./user-journeys-operators.md#3-testing--continuous-delivery)
