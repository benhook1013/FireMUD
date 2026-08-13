# FireMUD System Architecture: Testing Strategy

FireMUD employs a layered testing approach to keep services reliable while avoiding excessive CI/CD costs. This document describes the scope of each test type, the tooling in use, and how these tests fit into our development workflow.

Environment terminology in this document follows [Deployment Environments](./infrastructure/deployment-environments.md#canonical-environment-classes). “Hosted-assurance observability smoke” applies to hosted production profiles that claim externally verified availability or monitoring-resilient readiness; staging may run the same checks for rehearsal. Hobby, single-node, and small profiles may explicitly omit the off-cluster monitor and pager.
For `hobby-self-hosted`, equivalent operator-run evidence is acceptable when nightly automation is not practical. If the independent path is omitted, retained preflight records the degraded-detection warning while ordinary health, public-path smoke, and player-flow canaries remain applicable; omission alone does not block player traffic.
Minimum acceptable operator-run evidence for an omitted profile is one retained preflight or smoke record naming the detection posture, one retained result for the locally available public-path/player-flow checks, and one incident or deployment note recording who performed verification and when. A profile that claims independent detection must additionally retain current deadman, off-cluster public-path, and paging evidence.
Unless a more specific runbook defines another canonical evidence path for the same event, store that retained evidence under `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>.json` for first-live and reopen events, or alongside the matching recovery record under `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json` when the verification is part of restore hardening.
For first-live and reopen events, the illustrative `traffic-open-record/v1` shape in `design/architecture/system-architecture-backup-recovery.md#hobby-traffic-open-evidence` is the canonical example artifact for this retained evidence.
That artifact should minimally preserve the event identity (`deploymentRef` or `recoveryRef`), operator identity and timestamp, backing preflight or smoke evidence reference, declared detection posture, and results for all checks required by that posture.

Current repository automation includes a retained-evidence validator and runtime smoke harness for the mirrored player-flow canary / blackbox / deadman contract. It still assumes the stronger external-authority posture and must be aligned to accept an explicit omitted profile without synthesizing green external evidence. PR/main CI remains focused on static contract validation; environment-backed proof belongs to the profiles that claim it.

The following illustrates the current runner's legacy retained-evidence shape for a hosted-assurance smoke. Its extra observability-entrypoint fields are not part of the narrower target external contract:

```json
{
  "deploymentRef": "staging-2026-03-19-a",
  "verifiedAt": "2026-03-19T10:55:00Z",
  "verifiedBy": "operator@example",
  "preflightEvidenceRef": "ci://observability-smoke/2026-03-19T10:40:00Z",
  "externalAuthority": {
    "deadmanAuthority": {
      "status": "green",
      "evidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z",
      "target": "staging-deadman-authority",
      "checkRef": "check://staging/deadman"
    },
    "entrypointChecks": {
      "prometheus": {
        "status": "green",
        "evidenceRef": "pager://staging/prometheus/2026-03-19T10:51:00Z",
        "target": "staging-prometheus",
        "checkRef": "check://staging/prometheus"
      },
      "alertmanager": {
        "status": "green",
        "evidenceRef": "pager://staging/alertmanager/2026-03-19T10:51:00Z",
        "target": "staging-alertmanager",
        "checkRef": "check://staging/alertmanager"
      },
      "grafana": {
        "status": "green",
        "evidenceRef": "pager://staging/grafana/2026-03-19T10:51:00Z",
        "target": "staging-grafana",
        "checkRef": "check://staging/grafana"
      },
      "kibana_log_query": {
        "status": "green",
        "evidenceRef": "pager://staging/kibana-log-query/2026-03-19T10:51:00Z",
        "target": "staging-kibana-log-query",
        "checkRef": "check://staging/kibana-log-query"
      },
      "jaeger_query": {
        "status": "green",
        "evidenceRef": "pager://staging/jaeger-query/2026-03-19T10:51:00Z",
        "target": "staging-jaeger-query",
        "checkRef": "check://staging/jaeger-query"
      }
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
    ],
    "playerflow_canary_latency_ms": [
      {"flow": "command", "path": "websocket", "target": "staging-web-gateway", "value": 184}
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
    "verifiedFields": ["service", "traceId", "tenantId", "regionId", "characterId"]
  }
}
```

This example is illustrative rather than exhaustive. Equivalent retained evidence is acceptable as long as it preserves the same canonical checks and operator accountability.

The current runner accepts `--external-authority-evidence` for the stronger profile and only `--simulate` may synthesize that authority object. Its legacy requirement for externally checked observability entrypoints is implementation drift: the target external contract requires deadman freshness, real browser/WebSocket and Telnet paths, and off-cluster page delivery while allowing observability UIs to remain private.

---

## Testing Scope

Each microservice has its own unit and integration tests. Cross‑service scenarios are also covered in a dedicated suite. Load tests run independently using Gatling in a separate `load-testing` module. The cross‑service directories contain example tests that can be expanded as needed.

- **Unit tests** live under each service in `src/test/java/unit/`.
- **Integration tests** for that service live in `src/test/java/integration/` and may start Redis, Postgres, or other dependencies on demand.
- **Cross-service integration tests** exercise workflows that span multiple services. They live under `src/test/java/crossservice/` in each service and start companion containers with Testcontainers. Docker images for the cooperating services must be built (for example via `./gradlew buildDockerImages`) or pulled from GHCR. A unified `crossServiceTest` Gradle task runs them collectively, or run `./gradlew :service-name:test --tests "*CrossServiceIntegrationTest"`.
- Many of these tests are annotated with `@Testcontainers(disabledWithoutDocker = true)` so they are skipped when Docker is unavailable.
- **Load tests** reside in `dev-tools/load-testing/src/gatling` and simulate real usage patterns. Run them with `./gradlew :load-testing:gatlingRun`. Full high-concurrency load tests are typically run on demand; CI may run a small smoke-load profile to catch obvious regressions without blocking deployments.

Test data seeding strategies use the `dev-tools/seed/seed-test-data.sh` script to populate a minimal world for local testing, and an automated approach seeds data for integration tests.

Destructive reset and smoke proof must use a unique isolated tenant/game-instance namespace for each run and may mutate only data owned by that namespace. An environment-wide reset requires a dedicated disposable environment; a unique tenant/game identifier alone never authorizes destructive work against shared mutable data. A failed run retains its transcripts, logs, metrics, and reset/smoke status evidence for diagnosis before cleanup. A green run may be cleaned up only after its evidence is retained.

### Redis in Tests

Redis participates in several layers of the test strategy:

- **Unit tests** do not talk to Redis directly; any Redis interactions are mocked or exercised via small, in-memory fakes.
- **Service-level integration tests** may start a single coordination and cache Redis pair using Testcontainers:
  - Coordination tests use the same prefixes and Lua scripts as production (`tick:*`, `session:*`, `timer:*`, `retry:*`, `tick-executor-lease:*`), but run against a disposable coordination instance whose state is reset between tests or suites.
  - Cache/rate-limit tests use a distinct container or logical database for `inventory:*`, `view:room-look:*`, and `ratelimit:*` prefixes so eviction behaviour can be validated independently.
- **Cross-service integration tests** (for example, LOOK or LOGIN capability proofs) bring up Redis alongside multiple services and exercise canonical flows:
  - Testcontainers typically start a `redis-coord` and `redis-cache` pair, mirroring the role separation from `docker-compose` and Helm.
  - Tests treat coordination state as reset-tolerant within the suite: they rely on the tick replay and session recovery rules described in [System Architecture: Redis](./system-architecture-redis.md), but do not assume persistence across independent test runs.

In the default unit, integration, and cross-service test layers, coordination Redis behaves like the **“single-node without AOF (ephemeral coordination)”** profile from [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md):

- Coordination Redis instances used by tests are disposable and fully reset-tolerant; they do **not** validate tail-loss SLOs, AOF replay guarantees, or the long-running coordination buffer semantics described for persistent environments. Durable effect history and idempotency behavior are exercised primarily via PostgreSQL-ledger and domain-state checks, not by asserting properties of Redis AOF files.
- Cache/Rate-Limit Redis in tests mirrors the production role separation (dedicated cache instance) but is likewise treated as ephemeral and safe to reset between suites.
- A targeted production-like durability/fault harness is responsible for validating AOF restart, crash and bounded tail loss, persistent-volume behavior, replica promotion where applicable, and PostgreSQL-led reconciliation. Staging or an isolated production-shaped environment supplies the exact topology and storage identity; a real production incident is not required as proof.

The production-like harness is required before an environment claims a measured Redis write-loss window, AOF recovery, replica-promotion safety, or production recovery readiness. It is not a default gate for every ordinary pull request. Enabling AOF in all functional tests would add state, latency, and filesystem sensitivity without reproducing the production disk, replication, promotion, or Kubernetes failure domain, so it does not replace this bounded fault evidence.

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

Full high-concurrency load testing is executed on demand or in a dedicated environment and is not a default pull-request or deployment gate. CI may run a small smoke-load profile to catch obvious regressions, but it is functional/regression evidence rather than capacity, soak, or production-SLO proof.

### High-Concurrency Load Testing

Representative Gatling or equivalent campaigns measure service limits and uncover bottlenecks across the selected deployment profile, workload mix, topology, persistence settings, duration/soak, and failure conditions. “Full high-concurrency” is defined by that explicit campaign, not by one platform-global client count.

A campaign becomes a blocking gate only through an explicit capacity, autoscaling, admission, or SLO policy. The policy identifies the affected release/profile, workload, thresholds, artifact and environment identity, evidence freshness, and acceptable variance. Appropriate promotion triggers include publishing a capacity/SLO promise, enforcing a measured scaling or admission threshold, materially changing a throughput-critical path, increasing region density, tightening tick cadence, or allowing required evidence for a claimed profile to expire.

Once promoted, failure or stale evidence blocks only the affected release or profile. Ordinary bounded concurrency correctness remains a normal deterministic gate, while unrelated changes do not pay for a full load environment by default. Production-capacity campaigns must use the applicable persistence and replication topology; an ephemeral campaign can provide comparative regression data but cannot prove the production Redis loss or recovery envelope.

### Security Testing

OWASP ZAP baseline scans the built web client preview during CI to surface common web vulnerabilities. Gateway-target scanning should be added once the gateway exposes a stable CI scan target. Penetration tests and rate-limiting checks run before major releases.

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
    - Where online backups are enabled, backup metrics expose freshness, artifact-lineage validity, and restore-readability signals; recovery environments also expose bounded participant safe-disposition/convergence signals.
    - Maintenance/reset pause metrics may be tested by those workflows, but their presence is not routine backup proof.
    - These checks should confirm that metrics follow the cardinality guardrails defined in the Logging & Monitoring doc (for example, no `traceId` or `characterId` labels).
- **Alert wiring smoke tests**
  - Define one or more **test-only** alert rules (for example `ObservabilitySmokeTestAlert`) in non-production Alertmanager configurations with `alert_class="test"` and notifications routed only to low-noise channels or logging sinks, not to paging integrations.
  - Provide a short-lived probe in CI that intentionally pushes the corresponding test-only metric over its threshold in a non-production environment and verifies that Alertmanager receives and routes the alert with the expected labels (`service`, `severity="P2"`, `alert_class="test"`, `owner`, `runbook`).
  - These smoke tests can run as non-blocking or informational checks initially; once stable, they can be promoted to required checks for production-like environments, but they must never reuse P0/P1 production alert rules or target production Alertmanager instances directly.
  - For profiles requiring independent monitoring, verify that the independently hosted deadman path is receiving the in-cluster heartbeat signal. This check must not depend on Prometheus being healthy to succeed.
  - For those profiles, also verify the **authoritative external pager** path itself:
    - force a deadman-staleness test target or equivalent external-only failure mode,
    - verify the external monitoring product opens the expected non-production incident without depending on Prometheus rule evaluation,
    - verify the mirrored Prometheus signal matches the external monitor state once Prometheus is healthy again.
  - In prod-like observability smoke, also verify the canonical Logging & Admin alert-state behavior:
    - when Alertmanager is healthy, the UI/API presents its routed alert state without duplicating it from Prometheus rules,
    - when Alertmanager is intentionally unavailable while Prometheus remains healthy, the UI/API reports routed-alert unavailability and labels any bounded Prometheus snapshot as diagnostic rather than active alerts,
    - when a diagnostic snapshot exceeds its configured freshness budget, the UI/API reports it as `unknown`, and when both Alertmanager and Prometheus are unavailable it reports observability state unavailable.

- **Tracing checks**
  - In at least one non-production pipeline where Jaeger (or an OTLP-compatible trace backend) is available, run a small smoke test that:
    - Exercises a login flow and a representative gameplay command.
    - Verifies the presence of at least one `gamesession_handle_command` span with attributes such as `tenantId`, `regionId`, and `characterId`.
    - Verifies the presence of at least one `tick_execute` span in environments where ticks are enabled.
    - Verifies the presence of at least one TCP edge incident span (`tcpproxy_notify_disconnect` or `tcpproxy_connection`) in environments that expose the Telnet path.
    - Verifies `backup_pg_dump_snapshot` and `backup_verify_artifact` spans with matching environment/database, artifact, and tool lineage in environments that run online backups.
    - In recovery-drill pipelines, verifies `recovery_converge_participant` spans cover every declared and enabled participant and contain only approved safe dispositions before controlled reopen.
  - These checks may be skipped in environments without tracing backends but should be treated as required in pipelines that advertise tracing support, so span regressions are caught before production.

- **Structured log-field contract checks**
  - After a short synthetic login + command + tick smoke flow, assert that representative log lines from Gateway, Game Session, and TCP Proxy contain the structured fields required by the logging contract:
    - Required for request/tick handling paths: `service`, `traceId`, `correlationId`.
    - Required when known in context: `tenantId`, `regionId`.
    - Required when a player session is authenticated/bound: `characterId`.
  - Fail the check if any expected service path emits only free-form messages without these fields, because incident runbooks and Kibana drilldowns depend on those keys.
  - For profiles claiming indexed-log observability, verify end-to-end asynchronous queryability:
    - emit a unique structured synthetic record with a known `traceId`,
    - verify it reaches the supported operator query path within the profile's configured delay (two minutes is the starting target),
    - verify retrieval by `service` and `traceId`, plus applicable authorized context fields, using a narrowly scoped read identity,
    - fail the applicable promotion/release or indexed-observability claim when current evidence is absent, but never fail pod/gameplay readiness or player admission.
  - A hobby or small profile may instead retain console/journal retrieval evidence or an explicit indexed-search omission.

New services and features that add critical metrics or alerts should extend these observability tests where feasible so configuration errors are caught in CI rather than only in staging or production.

### Synthetic Player-Flow Canary Checks

Profiles that advertise continuous player-experience monitoring validate the synthetic canary path described in the Logging & Monitoring contract. Hobby and small profiles may instead retain evidence of local execution or an explicit omitted posture:

PR preview acceptance uses the same semantic authenticated/admitted/authoritative-`LOOK` outcomes through separate public Telnet and deployed first-party browser adapters. Shared assertions do not imply identical wire commands. Backend-only Game Session WebSocket smoke remains diagnostic and cannot replace either public path. See [ADR 0173](decisions/adr-0173-disposable-transport-complete-pr-preview-proof.md).

- Verify `playerflow_canary_success{flow="login",path=...}` exists for each exposed public path.
- Verify `playerflow_canary_success{flow="command",path=...}` exists for each exposed public path.
- Verify `playerflow_canary_latency_ms{flow="command",path=...}` is exported with millisecond semantics.
- Verify last-run freshness is emitted and a stopped, absent, or stale runner becomes monitoring degradation rather than silently disappearing.
- Verify canary labels remain low-cardinality (`flow`, `path`, `target`) and do not include account IDs, tenant IDs, player IDs, or trace IDs.
- Verify WebSocket and Telnet use separate restricted characters, both remain subject to security/moderation/audit, and validated synthetic traffic is excluded from product analytics and live-player SLO denominators.
- Verify the canonical canary alert path can be exercised in non-production without using production paging destinations:
  - one failed login sample does not page P0, while sustained fresh confirmed login-journey failure can trip `PlayerFlowCanaryLoginFailed` with `severity="P0"` (or the documented environment-equivalent canonical alert),
  - representative command failure trips `PlayerFlowCanaryCommandFailed` with `severity="P1"`,
  - controlled latency degradation trips `PlayerFlowCanaryLatencyHigh` with `severity="P1"`,
  - alert labels preserve `owner`, `severity`, and `runbook` from the architecture contract.
- These checks are required for profiles making the continuous monitoring claim because live-traffic SLIs alone are not sufficient in low-traffic periods.

#### Where These Checks Run (Decision)

To keep PR feedback fast while still proving real environments and recovery events, FireMUD uses three verification boundaries:

- **Deterministic change verification (PR + main CI)**:
  - Design-contract validation of dashboard/snippet consistency (for example `dev-tools/observability/validate-observability-contract.py`).
  - Markdown link + lint checks so runbook references do not rot.
- **Environment assurance (scheduled, release-bound, or staging/prod-like)**:
  - Alert routing smoke: trigger a test-only alert (`alert_class="test"`, `severity="P2"`) and verify Alertmanager routing and label preservation end-to-end.
  - External-authority smoke: verify the independently hosted monitoring system can page on deadman staleness or an equivalent external-only failure target without relying on Prometheus alert evaluation.
  - Private observability diagnostics: verify in-cluster or provider-native health checks without requiring Prometheus, Alertmanager, Grafana, Kibana/log-query, or Jaeger/trace-query to be externally reachable.
  - External edge blackbox smoke: verify the independent monitor exercises each real public entry path and that a forced probe failure (or equivalent test target) reaches the off-cluster notification path.
  - Player-flow canary smoke: verify the prod-like environment exposes mirrored `playerflow_canary_success` and `playerflow_canary_latency_ms` signals for login and the representative command path, and that a controlled non-production failure can trip the canary alert path.
  - Tracing smoke: run a login + representative command flow and verify at least one `gamesession_handle_command` span (and one `tick_execute` span where ticks run) is present in the trace backend. In environments that expose Telnet and online backups, also verify at least one `tcpproxy_notify_disconnect`/`tcpproxy_connection` span and matching `backup_pg_dump_snapshot` + `backup_verify_artifact` evidence.
  - Structured log contract smoke: verify sampled logs from critical paths contain required structured fields (`service`, `traceId`, `correlationId`, plus contextual `tenantId`/`regionId`/`characterId`).
  - Log pipeline queryability smoke: verify those same synthetic records are queryable end-to-end in the canonical Elasticsearch/Kibana or documented compatible log-query path.
  - Prometheus rules conformance smoke: query the Prometheus rules API and verify the required fallback/recording rules are loaded (tail-loss fallback, tick safety ratio recording, login success ratio recording, command p99 latency recording, entry-path availability recording, and chat delivery latency recording).
    - This includes the canonical dynamic tail-loss pair (`redis_coordination_tail_loss_budget_ms`, `redis_coordination_tail_loss_slo_breached`) and both short-window and 1-day entry-path availability recordings.
    - This includes preserving the bounded `command` label on the core-command latency recording rules so single-command regressions continue to alert.
    - This includes the replay-convergence set (`tick_effects_pending_oldest_age_seconds`, `tick_effects_replay_convergence_budget_seconds`, `tick_effects_replay_slo_breached`, and `tick_effects_replay_starved`) so ledger backlog alerting does not drift into environment-specific guesswork.
    - This also includes backup fallback signals (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_pipeline_recent_restore_drill_slo_breached`, `backup_artifact_lineage_invalid`, `backup_artifact_restore_unreadable`, `recovery_participant_convergence_blocked`) and the observability alert group (`firemud.alerts.observability`) so new platform-health alerts cannot drift out of the shared ruleset silently.
    - This also includes the tick-state projections (`current_tick_state`, `current_tick_terminal_at_ms`) and the aggregate remote follow-up recordings (`remote_followups_due_total`, `remote_followups_drain_lag_ms`, `remote_followups_backlog_over_budget_total`) so the observability contract stays aligned with the Redis and scaling docs without drifting back into forbidden tenant/region metric labels.
  - External-signal contract smoke: for a profile requiring independent monitoring, verify the canonical independent-signal contract from `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative`, or a documented compatibility mapping:
    - `entrypath_blackbox_probe_success{path,target}` for `path="websocket"` and `path="telnet"`, or a documented equivalent mapping.
    - `observability_deadman_heartbeat_timestamp_seconds{source}` or a documented equivalent external heartbeat signal.
    - `playerflow_canary_success{flow,path,target}` and `playerflow_canary_latency_ms{flow,path,target}` for the required login and representative command flows, or a documented equivalent mapping.
    - For the deadman path, verify the configured heartbeat interval, stale threshold, probe cadence, and resulting detection budget. The defaults are 60 and 180 seconds, not immutable constants.
- **Event-bound recovery and traffic-open proof**:
  - Bind restore, rewind, quarantine, participant convergence, and controlled-reopen results to the exact recovery event, artifact, environment, and current state.
  - Do not reuse a prior generic smoke or environment-assurance result as proof that the recovered environment is safe to reopen.
  - Keep mandatory post-rewind validation non-optional even for hobby and small profiles; automate it so one operator need not manually assemble the record.

A green earlier boundary never substitutes for a later boundary. Evidence records the exact artifact, environment, event/phase, expected-binding digest, tool version, timestamps, freshness, selected assurance profile, and content-addressed underlying tool output. Hobby and small profiles run unattended local evidence for capabilities they claim and explicitly record accepted omissions such as independent monitoring or indexed search; they do not claim the omitted assurance.

---

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Testing & Continuous Delivery](./user-journeys-operators.md#3-testing--continuous-delivery)
