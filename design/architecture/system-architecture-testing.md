# FireMUD System Architecture: Testing Strategy

FireMUD employs a layered testing approach to keep services reliable while avoiding excessive CI/CD costs. This document describes the scope of each test type, the tooling in use, and how these tests fit into our development workflow.

Environment terminology in this document follows [Deployment Environments](./infrastructure/deployment-environments.md#canonical-environment-classes). “Hosted-assurance observability smoke” applies to hosted production profiles that claim externally verified availability or monitoring-resilient readiness; staging may run the same checks for rehearsal. Hobby, single-node, and small profiles may explicitly omit the off-cluster monitor and pager under [ADR 0159](./decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md).
For `hobby-self-hosted`, equivalent operator-run evidence is acceptable when nightly automation is not practical. External-authority shape and freshness requirements for the deadman and public-path evidence apply only to `independent-required`. If the independent path is omitted, retained preflight records the explicit degraded-detection status while local health and exposed-public-path structural checks remain applicable; `independent-omitted` must not synthesize external deadman or public-path authority. Player-flow canaries remain conditional on the profile advertising that capability, and omission alone does not block player traffic.
Minimum acceptable operator-run evidence for an omitted profile is one retained preflight or smoke record naming the detection posture, one retained result for the locally available public-path checks and, when advertised, player-flow checks, and one incident or deployment note recording who performed verification and when. A profile that claims independent detection must additionally retain current deadman, off-cluster public-path, and paging evidence; independent monitoring does not inherently require player-flow canaries.
Store first-live and reopen evidence under `design/operations/deployments/hobby-self-hosted/traffic-open/<deployment-ref>/<deploymentEventId>.json`. Store restore-hardening verification evidence under `design/operations/deployments/hobby-self-hosted/recovery/<recovery-ref>.json` and link it from the event-bound traffic-open record; it is supporting recovery evidence, not an alternate traffic-open record.
Production first-live and reopen events use the event-bound traffic-open contract in [Backup Recovery Evidence and Compliance](./system-architecture-backup-recovery-evidence-and-compliance.md#production-traffic-open-backup-evidence); `hobby-self-hosted` first-live and reopen events use the profile-specific [hobby traffic-open evidence](./system-architecture-backup-recovery-evidence-and-compliance.md#hobby-traffic-open-evidence). The hobby operation-bound pre-release record reuses the production pre-release shape where specified by the owner document, but each profile's finalized projection, validation, payload, and `contentDigest` remain distinct. Each applicable schema requires its own `eventType`, `trafficOpenStatus`, recovery references, and finalized transition state.
The retained artifact should also preserve the operator identity and timestamp, backing preflight or smoke evidence reference, and the declared detection posture. Profiles claiming independent monitoring retain authoritative external pager/deadman and public-path verification for their exposed paths. Prometheus mirrors such as `entrypath_blackbox_probe_success` and `observability_deadman_heartbeat_timestamp_seconds` are optional; when the authoritative monitor publishes them, retain and verify them, while `playerflow_canary_*` is retained only when the profile advertises that capability. Omitted profiles retain locally available blackbox evidence only for their declared exposed paths, omit the deadman mirror, and retain player-flow evidence only when advertised, plus the explicit degraded-detection warning.

## Implementation Status

Current repository automation includes the profile-aware retained-evidence validator `python3 dev-tools/observability/validate-player-experience-smoke-evidence.py <evidence.json>` and runtime harness `dev-tools/observability/run-player-experience-smoke.py`. The runner writes the evidence artifact but does not invoke the validator itself: operators may run it separately, and preflight's promotion/recovery consumers revalidate each retained artifact, including `independent-omitted` evidence and profiles that omit the player-flow canary. Omitted capabilities must omit their capability-specific signals rather than bypassing canonical schema and authority checks. When the player-flow capability is advertised, the current harness runs and retains the complete login/command canary for every declared exposed path in one invocation. It does not execute the alert evaluator or notification path, so its `canaryAlerts` records remain `not_exercised` and cannot authorize a gate until the environment's separate alert-path exercise has supplied actual passing results. Only `independent-required` profiles retain `deadmanAuthority`, current external-authority freshness fields, and a complete bounded external `publicPathChecks` map with `not_applicable` for non-exposed paths; `independent-omitted` retains its complete exposed-path declaration, local structural results, and explicit degraded reason without synthesizing external authority or a deadman mirror. PR/main CI remains focused on static metric-cardinality and observability-contract validation; environment-backed checks belong to profiles that claim independent monitoring.

Illustrative retained evidence shape for a hosted-assurance observability smoke; an omitted profile must retain `externalAuthority: {"profile": "independent-omitted", "reason": "...", "exposedPublicPlayerPaths": ["websocket"]}` and declare `capabilities: {"prometheusMirrors": "omitted", "playerFlowCanary": "omitted"}` without deadman or public-path authority records. For promotion-bound evidence, `deploymentRef` is the exact staging overlay commit SHA (`stagingOverlayCommitSha`) and `deploymentEventId` is the distinct UUID selected by the promotion attestation, so evidence cannot be reused across two applies of the same overlay commit. Standalone local evidence may use another non-empty reference, but that artifact is not promotion evidence.

An `independent-omitted` profile may instead advertise player-flow canaries as a separate valid form:

```json
{
  "externalAuthority": {
    "profile": "independent-omitted",
    "reason": "single-node deployment uses operator-dependent outage detection",
    "exposedPublicPlayerPaths": ["websocket"],
    "detectionBudgetSeconds": 195
  },
  "capabilities": {
    "prometheusMirrors": "omitted",
    "playerFlowCanary": "advertised"
  }
}
```

This advertised-canary form retains the complete local canary mirror family and profile budget, but no deadman or external `publicPathChecks` authority; the budget is local canary timing only. Together with the omitted-canary form above, these are the two valid `independent-omitted` canary forms.

```json
{
  "deploymentRef": "<staging-overlay-commit-sha>",
  "deploymentEventId": "11111111-2222-4333-8444-555555555555",
  "verifiedAt": "2026-03-19T10:55:00Z",
  "verifiedBy": "operator@example",
  "preflightEvidenceRef": "ci://observability-smoke/2026-03-19T10:40:00Z",
  "executionMode": "live",
  "externalAuthorityProvenance": "retained-external",
  "capabilities": {
    "prometheusMirrors": "published",
    "playerFlowCanary": "advertised"
  },
  "externalAuthority": {
    "profile": "independent-required",
    "exposedPublicPlayerPaths": ["websocket", "telnet"],
    "detectionBudgetSeconds": 195,
    "staleThresholdSeconds": 180,
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:54:00Z",
    "observedStalenessSeconds": 60,
    "deadmanAuthority": {
      "status": "green",
      "evidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z",
      "pageEvidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z/delivery",
      "target": "staging-deadman-authority",
      "checkRef": "check://staging/deadman"
    },
    "publicPathChecks": {
      "websocket": {
        "status": "green",
        "evidenceRef": "probe://staging/websocket/2026-03-19T10:51:00Z",
        "pageEvidenceRef": "pager://staging/websocket/2026-03-19T10:50:00Z/delivery",
        "target": "staging-websocket",
        "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z",
        "observedProbeAgeSeconds": 120
      },
      "telnet": {
        "status": "green",
        "evidenceRef": "probe://staging/telnet/2026-03-19T10:51:00Z",
        "pageEvidenceRef": "pager://staging/telnet/2026-03-19T10:50:00Z/delivery",
        "target": "staging-telnet",
        "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z",
        "observedProbeAgeSeconds": 120
      }
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "gateway", "value": 1},
      {"path": "telnet", "target": "tcp_proxy", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917640
    },
    "playerflow_canary_success": [
      {"flow": "login", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1},
      {"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1},
      {"flow": "login", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1},
      {"flow": "command", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1}
    ],
    "playerflow_canary_latency_ms": [
      {"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 184},
      {"flow": "command", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 221}
    ],
    "playerflow_canary_last_run_timestamp_seconds": [
      {"flow": "login", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1773917690},
      {"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1773917690},
      {"flow": "login", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1773917690},
      {"flow": "command", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1773917690}
    ],
    "playerflow_canary_freshness_budget_seconds": {
      "profile": "independent-required",
      "value": 195
    }
  },
  "canaryAlerts": [
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryCommandFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryLatencyHigh", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryEvidenceStale", "severity": "P1", "exerciseResult": "passed"}
  ],
  "logPipelineQueryability": {
    "selectedProfile": "staging",
    "capability": "indexed-log-observability",
    "backend": "elasticsearch",
    "storageTarget": "firemud-logs-*",
    "recordId": "log-smoke-11111111-2222-4333-8444-555555555555",
    "service": "game-session-service",
    "traceId": "9c8d7e6f5a4b3210",
    "queryPath": "kibana-saved-search:player-incident-drilldown",
    "configuredDelayTargetSeconds": 120,
    "emittedAt": "2026-03-19T10:53:20Z",
    "retrievedAt": "2026-03-19T10:54:34Z",
    "observedDelaySeconds": 74,
    "result": "passed",
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "evidenceFreshnessBudgetSeconds": 7200,
    "evidenceExpiresAt": "2026-03-19T12:55:00Z",
    "evidenceRef": "query-proof://staging/log-smoke-11111111-2222-4333-8444-555555555555",
    "verifiedAt": "2026-03-19T10:55:00Z",
    "verifiedFields": ["recordId", "service", "traceId", "tenantId", "gameInstanceId", "regionId", "characterId"]
  }
}
```

This example is illustrative rather than exhaustive. Its `passed` alert records represent actual alert-path exercises, not values inferred from configured alert families or injected canary metrics. Equivalent retained evidence is acceptable as long as it preserves the applicable profile's canonical checks and operator accountability. Each exposed path's `observedProbeAgeSeconds` is the direct, chronology-validated difference from `evidenceObservedAt` to `lastSuccessfulProbeObservedAt`, within the established numeric tolerance, and a green path must remain within `detectionBudgetSeconds`; non-exposed paths remain exactly `not_applicable`. The target independent contract is deadman freshness, each declared exposed browser/WebSocket or Telnet path, and off-cluster page delivery. Retained smoke evidence carries `externalAuthority.deadmanAuthority`, `externalAuthority.observedStalenessSeconds`, and the diagnostic heartbeat timestamp mirror. The external monitor and deployment overlay separately publish and verify `observability_deadman_stale{profile="independent-required"}` as the canonical deadman mirror; that gauge is not retained as a field in this illustrative smoke payload. The heartbeat timestamp remains diagnostic and cannot substitute for external deadman authority or page delivery. It does not require Prometheus, Alertmanager, Grafana, Kibana, or Jaeger to be externally reachable.

The `logPipelineQueryability` object above is the default indexed-profile form under [ADR 0162](./decisions/adr-0162-profile-aware-asynchronous-end-to-end-log-queryability-evidence.md) and the canonical [Log Pipeline Queryability Contract](./system-architecture-logging-monitoring.md#log-pipeline-queryability-contract). Its closed capability vocabulary is `indexed-log-observability`, `console-journal-log-observability`, or `log-queryability-omitted`; non-omitted evidence uses `result="passed"` or `result="failed"`, while `result="not_applicable"` is reserved for explicit omission. `backend` and `storageTarget` identify where a non-omitted record is retained; `queryPath` identifies the final supported operator surface and must not contain only an index or stream convention. A `passed` result is valid only when the fresh unique `recordId` and known `traceId` identify the exact retrieved record, `verifiedFields` records every asserted source field, the timestamps are chronological (`emittedAt <= retrievedAt <= evidenceObservedAt <= verifiedAt`), `observedDelaySeconds` equals `retrievedAt - emittedAt`, the observed delay is no greater than `configuredDelayTargetSeconds`, and the retained evidence has not passed `evidenceExpiresAt`. Every result carries a positive finite, profile-resolved `evidenceFreshnessBudgetSeconds`; `evidenceExpiresAt` must equal `evidenceObservedAt + evidenceFreshnessBudgetSeconds`. `verifiedAt` is the trusted evaluator completion time, so source timestamps cannot be future-dated relative to it. A compatible indexed backend retains the same fields with its mapped storage target and supported query path. A reduced profile that advertises console or journal retrieval proves the same unique-record retrieval and timing through its declared backend, storage target, and final operator query path. A failed result is non-authorizing in readiness validation and is accepted only with the explicit incident/failure-evidence mode. A profile that explicitly omits queryability records the selected profile, `capability="log-queryability-omitted"`, `result="not_applicable"`, its omission reason, observation/expiry timestamps, and an evidence reference without fabricating indexed or retrieval fields such as backend, storage target, query path, emission/retrieval timestamps, delay measurements, record identity, source identity, or verified fields.

Use `python3 dev-tools/observability/run-player-experience-smoke.py --external-authority-evidence <authority.json> --evidence-out <evidence.json> [--metrics-out <mirrored.prom>]` to generate the journey and mirrored-signal part of hosted-assurance smoke evidence, incorporate actual results from the separately exercised canary alert path, then run `python3 dev-tools/observability/validate-player-experience-smoke-evidence.py <evidence.json>` before attaching the completed result to a traffic-open or recovery record. `authority.json` must be the retained profile-aware result from the authoritative external monitor for the deadman and declared public paths, or an explicit `independent-omitted` record with its degraded-detection reason. Only `--simulate` may synthesize that authority object.

The default runner intake and validator invocation are readiness-strict: every exposed path and advertised canary flow must be green, and every required alert family must have an actually exercised `passed` result, so promotion and recovery consumers fail closed on a current failure or an unproved alert path. For incident capture and alert-path diagnosis only, pass `--allow-failure-evidence` to the runner when the retained source authority is red and to the validator when checking the resulting artifact. That mode accepts structurally complete current red deadman/public-path authority, zero-valued failed or prerequisite-blocked path/canary records, and `failed` or `not_exercised` alert-path results. It keeps external-authority shape, provenance, chronology, freshness, and record completeness strict and must not be used as traffic-open or recovery authority.

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
- **Cross-service integration tests** (for example, LOOK or LOGIN capability proofs) bring up Redis alongside multiple services and exercise canonical flows:
  - Testcontainers typically start a `redis-coord` and `redis-cache` pair, mirroring the role separation from `docker-compose` and Helm.
  - Tests treat coordination state as reset-tolerant within the suite: they rely on the tick replay and session recovery rules described in [System Architecture: Redis](./system-architecture-redis.md), but do not assume persistence across independent test runs.

In the default unit, integration, and cross-service test layers, coordination Redis behaves like the **“single-node without AOF (ephemeral coordination)”** profile from [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md):

- Coordination Redis instances used by tests are disposable and fully reset-tolerant; they do **not** validate tail-loss SLOs, AOF replay guarantees, or the long-running coordination buffer semantics described for persistent environments. Durable effect history and idempotency behavior are exercised primarily via PostgreSQL-ledger and domain-state checks, not by asserting properties of Redis AOF files.
- Cache/Rate-Limit Redis in tests mirrors the production role separation (dedicated cache instance) but is likewise treated as ephemeral and safe to reset between suites.
- A targeted production-like durability/fault harness is responsible for validating AOF restart, crash and bounded write-loss exposure, persistent-volume behavior, replica promotion where applicable, and PostgreSQL-led reconciliation. Staging or an isolated production-shaped environment supplies the exact topology and storage identity; a real production incident is not required as proof.

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

### Retention Compatibility and Cleanup Proof

Cross-service persistence tests must exercise the shared retention-class boundary rather than proving only an elapsed TTL. For each command/retry/recovery family in scope, focused tests should:

- keep old non-terminal, inconsistent, quarantined, or reconciliation-required work ineligible for cleanup;
- reject cleanup while a blocking reference remains or the family-specific safe watermark has not passed, even when the configured minimum duration has elapsed;
- preserve the compact identity, request digest, terminal outcome, and required lineage while any supported client or internal retry, reconnect, replay, restore, or reconciliation path can redeliver the logical action;
- verify producer/consumer compatibility by refusing a consumer receipt or effect-guard horizon shorter than the producer's supported redelivery horizon;
- restore or replay from the oldest supported recovery point after cleanup and prove that no logical command or effect can be applied twice; and
- allow earlier payload minimization only when replay, investigation, and governance no longer require the removed content and the retained compact receipt still prevents duplication.

These are target proof obligations, not a claim that the current automated suite closes the retention boundary. The canonical eligibility and compatibility rules are in [ADR 0163](./decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md), operations remain in the [Scaling Runbook](./system-architecture-scaling-runbook.md#data-retention-and-high-churn-tables), and current implementation/proof gaps remain in [Shared Runtime Contracts and Persistence](../project-management/implementation-tracking/shared-runtime-contracts-and-persistence.md).

---

## CI/CD Integration

GitHub Actions executes formatting and lint checks, builds the code, and runs all unit and integration tests via `:service-name:check` for each module. Coverage reports are generated and a Trivy security scan is executed. See the [CI/CD Pipeline](./system-architecture-cicd.md) document for the workflow definition.

Full high-concurrency load testing is executed on demand or in a dedicated environment and is not a default pull-request or deployment gate. CI may run a small smoke-load profile to catch obvious regressions, but it is functional/regression evidence rather than capacity, soak, or production-SLO proof.

### High-Concurrency Load Testing

Representative Gatling or equivalent campaigns measure service limits and uncover bottlenecks across the selected deployment profile, workload mix, topology, persistence settings, duration or soak, and failure conditions. “Full high-concurrency” is defined by that explicit campaign, not by one platform-global client count.

A campaign becomes a blocking gate only through an explicit capacity, autoscaling, admission, or SLO policy. The policy identifies the affected release/profile, workload, thresholds, artifact and environment identity, evidence freshness, and acceptable variance. Appropriate promotion triggers include publishing a capacity/SLO promise, enforcing a measured scaling or admission threshold, materially changing a throughput-critical path, increasing region density, tightening tick cadence, or allowing required evidence for a claimed profile to expire.

Once promoted, failure or stale evidence blocks only the affected release or profile. Ordinary bounded concurrency correctness remains a normal deterministic gate, while unrelated changes do not pay for a full load environment by default. Production-capacity campaigns must use the applicable persistence and replication topology; an ephemeral campaign can provide comparative regression data but cannot prove the production Redis loss or recovery envelope.

### Security Testing

OWASP ZAP baseline scans the built web client preview during CI to surface common web vulnerabilities. Gateway-target scanning should be added once the gateway exposes a stable CI scan target. Penetration tests and rate-limiting checks run before major releases.

### Observability Tests

In addition to functional, load, and security tests, FireMUD treats observability wiring as part of the system contract. The routed-alert and degraded-diagnostic boundary follows [ADR 0158](./decisions/adr-0158-simplified-observability-degradation-without-fallback-alert-authority.md), and profile-dependent external monitoring follows [ADR 0159](./decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md). A minimal set of checks should validate that critical metrics and alerts are present and correctly labeled:

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
  - For profiles requiring independent monitoring, verify that the independently hosted deadman / meta-monitoring path is receiving the in-cluster heartbeat signal. This check must not depend on Prometheus being healthy to succeed.
  - For those profiles, also verify the **authoritative external pager** path itself:
    - force a deadman-staleness test target or equivalent external-only failure mode,
    - verify the external monitoring product opens the expected non-production incident without depending on Prometheus rule evaluation,
    - when the authoritative monitor publishes a Prometheus mirror, verify that mirror matches the external monitor state once Prometheus is healthy again; a required profile does not fail mirror verification when no mirror is published.
  - In observability smoke where Alertmanager is available, also verify the canonical Logging & Admin alert-state behavior:
    - when Alertmanager is healthy, the UI/API reports `source="alertmanager"` (or an equivalent explicit source marker) and does not duplicate the same condition from Prometheus diagnostics,
    - when Alertmanager is intentionally made unavailable while Prometheus remains healthy, the UI/API reports routed-alert unavailability and labels any bounded Prometheus snapshot as diagnostic rather than active alerts,
    - when a diagnostic snapshot exceeds its configured freshness budget, the UI/API reports it as `unknown`, and when both Alertmanager and Prometheus are unavailable it reports observability state unavailable.

- **Tracing checks**
  - In at least one non-production pipeline where Jaeger (or an OTLP-compatible trace backend) is available, run a small smoke test that:
    - Exercises a login flow and a representative gameplay command.
    - When the environment advertises and independently proves the corresponding gameplay-command workflow-tracing capability, verifies the presence of at least one `gamesession_handle_command` span with the applicable `tenantId`, `gameInstanceId`, `regionId`, and `characterId` attributes.
    - When the environment advertises and independently proves the corresponding tick workflow-tracing capability, verifies the presence of at least one `tick_execute` span when ticks are enabled.
    - When the environment advertises and independently proves the corresponding TCP-edge workflow-tracing capability, verifies the presence of at least one TCP edge incident span (`tcpproxy_notify_disconnect` or `tcpproxy_connection`) when the Telnet path is exposed.
    - Only when the environment advertises and independently proves the specific `backup` workflow-tracing capability, verifies `backup_pg_dump_snapshot` and `backup_verify_artifact` spans with matching environment/database, artifact, and tool lineage.
    - Only when the environment advertises and independently proves the specific `recovery` workflow-tracing capability, verifies `recovery_converge_participant` spans cover every declared and enabled participant and contain only approved safe dispositions before controlled reopen.
  - A workflow-span assertion runs only when both conditions hold: the environment capability descriptor names that exact workflow and its immutable end-to-end proof shows semantic spans, context propagation, collector ingestion, and supported queries. Generic Jaeger/OTLP availability, an environment variable, a sample span, or an externally supplied evidence reference is not proof. When either condition is absent, skip the span assertion and use the metrics/log fallback; do not make tracing a hidden readiness dependency.
  - The four gameplay identity attributes are required together only for the applicable `gamesession_handle_command` workflow assertion; they are not a universal requirement for login, TCP-edge, backup, recovery, or generic RPC span checks.

- **Structured log-field contract checks**
  - After a short synthetic login + command + tick smoke flow, assert that representative log lines from Gateway, Game Session, and TCP Proxy contain the structured fields required by the logging contract:
    - Required for request/tick handling paths: `service`, `traceId`, `correlationId`.
    - Required when known and applicable to the emitting operation: `tenantId`, `gameInstanceId`, and `regionId`.
    - Required when a player session is authenticated/bound and the field is known: `characterId`.
  - The smoke harness must not fabricate or post-enrich `tenantId`, `gameInstanceId`, `regionId`, or `characterId` merely to satisfy the contract. Assert each contextual field only on records whose source operation knows and applies that context.
  - Fail the check if any expected service path emits only free-form messages without the fields required for that path; do not fail a record because an inapplicable contextual ID is absent.
  - For profiles claiming indexed-log observability, verify end-to-end asynchronous queryability:
    - emit a unique structured synthetic record with a known `traceId`,
    - poll until it reaches the supported operator query path within the profile's configured delay; two minutes is the starting calibration target rather than an immutable constant,
    - verify retrieval by `service` and `traceId`, plus each applicable authorized contextual field when it is present at the source, using a narrowly scoped read identity; do not fabricate or post-enrich IDs for query assertions,
    - fail the applicable promotion/release or indexed-observability claim when current evidence is absent, but never fail pod/gameplay readiness or player admission.
  - A hobby or small profile may instead retain console/journal retrieval evidence or an explicit indexed-search omission.

New services and features that add critical metrics or alerts should extend these observability tests where feasible so configuration errors are caught in CI rather than only in staging or production.

### Synthetic Player-Flow Canary Checks

Profiles that advertise the player-flow canary capability must also validate the synthetic canary path described in the Logging & Monitoring contract:

- Verify `playerflow_canary_success{flow="login",path=...,target=...,profile=...}` and `playerflow_canary_success{flow="command",path=...,target=...,profile=...}` exist for each exposed public path.
- Verify `playerflow_canary_latency_ms{flow="command",path=...,target=...,profile=...}` is exported with millisecond semantics, and `playerflow_canary_last_run_timestamp_seconds{flow,path,target,profile}` is current for each required flow/path.
- Verify the profile-derived `playerflow_canary_freshness_budget_seconds{profile}` exactly matches the declared `externalAuthority.detectionBudgetSeconds` whenever `playerFlowCanary=advertised`, regardless of whether the optional Prometheus mirror or alert installation is present. Installed canonical Prometheus canary alerts additionally evaluate that exact gauge. For independently monitored profiles, the value is also the authoritative external-monitoring budget; for `independent-omitted`, compare it with the declared canary timing budget as a local check only because it does not establish external-monitoring authority. Preserve the independent-required versus independent-omitted distinction in either case.
- Verify canary labels remain low-cardinality (`flow`, `path`, `target`, `profile`) and do not include account IDs, tenant IDs, player IDs, or trace IDs.
- Verify WebSocket and Telnet use separate restricted traffic controls, both remain subject to authentication, abuse controls, authorization, moderation, security monitoring, and durable audit, and validated synthetic traffic is excluded only from product analytics, ordinary player-behavior interpretation, and live-player SLO denominators.
- Verify the canonical canary alert path can be exercised in non-production without using production paging destinations:
  - one failed login sample does not page P0; the current two-minute canary rule trips `PlayerFlowCanaryLoginFailed` with `severity="P1"`, while a separately proved sustained, fresh, confirmed complete-login policy may promote the incident to P0,
  - representative command failure trips `PlayerFlowCanaryCommandFailed` with `severity="P1"`,
  - controlled latency degradation trips `PlayerFlowCanaryLatencyHigh` with `severity="P1"`,
  - stale available run evidence trips `PlayerFlowCanaryEvidenceStale` with `severity="P1"` and is treated as unknown/degraded rather than a player-flow failure,
  - when canary result series are present but the matching profile budget series is removed, `PlayerFlowCanaryFreshnessBudgetMissing` trips with `severity="P1"`, retaining the bounded `profile` label (and its canonical Prometheus/platform/runbook routing labels) without inventing flow, path, or target labels for the profile-grouped condition,
  - target state: once a deployment-owned expected-series inventory exists, omitting one advertised flow/path/target/profile run timestamp trips `PlayerFlowCanaryEvidenceMissing` with `severity="P1"`, retaining only the bounded expected-tuple labels (`flow`, `path`, `target`, `profile`) and preserving the declared profile semantics; this is a non-production proof obligation, not a claim that the expected-series inventory or alert is implemented today,
  - alert labels preserve `owner`, `severity`, and `runbook` from the architecture contract.
- These checks are required only for profiles advertising player-flow canaries because live-traffic SLIs alone are not sufficient in low-traffic periods. Independent-monitoring profiles still retain their required deadman and public-path evidence even when they do not advertise player-flow canary metrics.
- The canary timing-budget and target-state missing-evidence checks apply to both `independent-required` and `independent-omitted` profiles when player-flow canaries are advertised. For `independent-omitted`, the profile label and canary timing budget remain meaningful local checks only; they do not establish external-monitoring authority, and the deadman/external-authority shape and freshness requirements above do not apply. Omission of the canary capability remains `not_applicable`.

#### Where These Checks Run (Decision)

To keep PR feedback fast while still proving real environments and recovery events, FireMUD uses three verification boundaries:

- **Deterministic change verification (PR + main CI)**:
  - Design-contract validation of dashboard/snippet consistency (for example `dev-tools/observability/validate-observability-contract.py`).
  - Markdown link + lint checks so runbook references do not rot.
- **Environment assurance (nightly or staging-gated, for profiles that claim it)**:
  - Alert routing smoke: trigger a test-only alert (`alert_class="test"`, `severity="P2"`) and verify Alertmanager routing and label preservation end-to-end.
  - For profiles requiring independent monitoring, external-authority smoke verifies that the independently hosted monitoring system can page on deadman staleness or an equivalent external-only failure target without relying on Prometheus alert evaluation.
  - Private observability diagnostics may verify in-cluster or provider-native health checks without requiring Prometheus, Alertmanager, Grafana, Kibana/log-query, or Jaeger/trace-query to be externally reachable.
  - For profiles requiring independent monitoring, external edge blackbox smoke verifies an independent synthetic probe for each real public entry path and that a forced probe failure (or equivalent test target) reaches the off-cluster notification path.
  - Player-flow canary smoke is required only where the profile advertises that capability. A profile omitting independent monitoring records degraded detection rather than claiming external deadman/public-path authority; a profile requiring independent monitoring retains deadman and public-path evidence whether or not it advertises player-flow canaries.
  - Tracing smoke: run a login + representative command flow and verify the applicable advertised-and-proved workflow capabilities before asserting named spans. In environments that advertise and prove the `backup` workflow, verify matching `backup_pg_dump_snapshot` + `backup_verify_artifact` evidence; in environments that advertise and prove the `recovery` workflow, verify participant-convergence spans. Without those specific proofs, retain the metrics/log fallback and do not assert workflow spans.
  - Structured log contract smoke: verify sampled logs from critical paths contain `service`, `traceId`, and `correlationId`, plus contextual `tenantId`/`gameInstanceId`/`regionId`/`characterId` only when each is known and applicable; smoke evidence must not fabricate or post-enrich those IDs.
  - Log pipeline queryability evidence follows the selected logging posture: an indexed profile verifies the fresh unique synthetic record end to end through the default Elasticsearch/Kibana path or its documented compatible mapping; a reduced profile proves its declared console/journal retrieval path; and an explicit indexed-search omission records `not_applicable` without running or passing an indexed check. Use contextual IDs only when they are present and applicable, and gate only the promotion, release, or observability claim advertised by that profile.
  - Prometheus rules conformance smoke: query the Prometheus rules API and verify the required recording rules and Alertmanager evaluation inputs are loaded (tail-loss diagnostic, tick safety ratio recording, login success ratio recording, command p99 latency recording, entry-path availability recording, and chat delivery latency recording). These rules are not a second routed-alert authority.
    - This includes the current cadence-derived tail-loss compatibility pair (`redis_coordination_tail_loss_budget_ms`, `redis_coordination_tail_loss_slo_breached`) as diagnostic or Alertmanager-evaluation inputs, not measured-SLO proof. When implemented, the target measured pair (`redis_unreplicated_write_window_slo_ms`, `redis_unreplicated_write_window_slo_breached{scope}`) is checked separately, alongside both short-window and 1-day entry-path availability recordings.
    - This includes preserving the bounded `service` and `command` labels on the core-command latency recording rules so service-specific or single-command regressions continue to alert.
    - This includes the replay-convergence set (`tick_effects_pending_oldest_age_seconds`, `tick_effects_replay_convergence_budget_seconds`, `tick_effects_replay_slo_breached`, and `tick_effects_replay_starved`) so ledger backlog alerting does not drift into environment-specific guesswork.
    - This also includes backup diagnostic signals (`backup_pipeline_recent_backup_slo_breached`, `backup_pipeline_recent_verification_slo_breached`, `backup_pipeline_recent_restore_drill_slo_breached`, `backup_artifact_lineage_invalid`, `backup_artifact_restore_unreadable`) and the Alertmanager evaluation group (`firemud.alerts.observability`) so new platform-health rules cannot drift out of the shared ruleset silently. When the environment advertises and proves the recovery-controller capability, also require `recovery_participant_convergence_blocked`, `recovery_environment_convergence_blocked`, `recovery_participant_convergence_coverage_missing`, and `recovery_participant_convergence_source_missing`; otherwise validate the explicit unknown/unavailable state and follow the owner-evidence path rather than requiring those recordings.
    - This also includes the tick-state projections (`current_tick_state`, `current_tick_terminal_at_ms`) and the aggregate remote follow-up recordings (`remote_followups_due_total`, `remote_followups_drain_lag_ms`, `remote_followups_backlog_over_budget_total`) so the observability contract stays aligned with the Redis and scaling docs without drifting back into forbidden tenant/game-instance/region metric labels.
  - For profiles requiring independent monitoring, external-signal contract smoke verifies the canonical independent-signal contract from `design/architecture/system-architecture-logging-monitoring.md#external-probe-and-deadman-contract-normative`, or a documented compatibility mapping:
    - Iterate over the complete `exposedPublicPlayerPaths` set. For each exposed path whose authoritative external monitor publishes a Prometheus mirror, verify the matching `entrypath_blackbox_probe_success{path,target}` series or documented equivalent mapping; record each non-exposed bounded path as `not_applicable`. The authoritative off-cluster public-path evidence remains required for every exposed path even when no mirror is published.
    - When the authoritative external monitor publishes a Prometheus heartbeat mirror, verify `observability_deadman_heartbeat_timestamp_seconds{source}` or its documented equivalent external heartbeat signal. The external pager/deadman proof remains required without that optional mirror.
    - When the profile advertises player-flow canaries, verify the profile-labelled success, latency, last-run records, and an exact-match profile-derived freshness-budget gauge for the required login and representative command flows on every exposed path, regardless of optional Prometheus mirror or alert installation. When canonical Prometheus canary alerts are installed/evaluated, verify that those alerts also evaluate the matching freshness-budget gauge; an omitted canary capability omits this family.
    - For the deadman path, consume the canonical external-monitoring evidence's retained observation timestamps, observed staleness, and profile-derived maximum detection budget; do not impose a universal heartbeat-to-threshold formula.

- **Event-bound recovery and traffic-open proof**:
  - Bind restore, rewind, quarantine, participant convergence, and controlled-reopen results to the exact recovery event, artifact, environment, and current state.
  - Do not reuse a prior generic smoke or environment-assurance result as proof that the recovered environment is safe to reopen.
  - Keep mandatory post-rewind validation non-optional even for hobby and small profiles; automate it so one operator need not manually assemble the record.

Profiles that omit independent monitoring retain the omission and degraded-detection warning in preflight/incident evidence and do not claim the external deadman/public-path checks above; player-flow canary checks remain conditional solely on that capability being advertised. This split ensures that contract drift is caught on every change, while backend-dependent checks run only where the profile advertises the capability and Alertmanager/Jaeger are actually available.

A green earlier boundary never substitutes for a later boundary. Evidence records the exact artifact, environment, event or phase, expected-binding digest, tool version, timestamps, freshness, selected assurance profile, and content-addressed underlying tool output. Hobby and small profiles run unattended local evidence for capabilities they claim and explicitly record accepted omissions such as independent monitoring or indexed search; they do not claim the omitted assurance.

### Packet 4 Lifecycle And Authoring Proof Obligations

The following focused proofs are required by the accepted target contracts; their presence here does not claim that the current implementation has closed them. Run them through the shared [validation and runtime-proof workflow](../developer-workflows/validation-and-runtime-proof.md), which selects the applicable checks and evidence location; this section is only the Packet 4 obligation index and does not duplicate a result ledger.

- Replacement must cover shared, isolated, and fresh-playtest namespace modes; exhaustive S1/S2/S3 owner classification; unknown/unowned/unclassified blocking; owner-validated mapping application rather than an echoed `remapSetId`; active-instance fencing; stale preflight; concurrent cutover; `bindingGeneration` CAS proof for stale-generation rejection, monotonic next-generation rebinding, and old-region-binding rejection after success ([Session and Region-Binding Contract](./system-architecture-redis.md#session-and-region-binding-contract)); and cleanup acknowledgement before old-instance termination ([ADR 0122](./decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md)).
- World lifecycle must cover database row/epoch CAS, Temporal retry/restart, termination from preparation, `FAILED_PRE_ACTIVATION` with incomplete cleanup, newly registered/unavailable owners, refusal to reach `TERMINATED` without every acknowledgement, stuck-state telemetry, and routine gameplay without a Temporal call ([ADR 0123](./decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md)).
- Profiles must cover materialized editable Draft content, exact baseline/source lineage, conservative unchanged-only upgrades, explicit conflict resolution, and no runtime fallback ([ADR 0124](./decisions/adr-0124-materialized-starter-profiles-with-conservative-draft-upgrades.md)).
- Portability tests must verify the current unsupported boundary is surfaced without pretending that whole-game export/import or Git/filesystem synchronization exists ([ADR 0125](./decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md)).
- Model-assisted authoring must prove scoped tool access, exact base/digest proposal binding, isolation/untrusted handling, human acceptance, and ordinary authoring when the model path is unavailable ([ADR 0126](./decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md)).
- Equipment publication/runtime tests must fail closed for incomplete vocabulary/schema/bindings, validate occupancy against the published digest, and exercise explicit cutover remapping ([ADR 0127](./decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md)).
- Plugin provenance tests must preserve ADR 0111 as trust/runtime authority while checking service-local signed-intake evidence, signer/key lifecycle, capability/policy projection, and incomplete-validation behavior ([ADR 0128](./decisions/adr-0128-game-design-plugin-trust-provenance.md)).
- Draft concurrency tests must cover exact-base/digest mismatch, owner-local CAS conflicts, durable per-owner outcomes, synchronized read-fence visibility, retry convergence, no silent merge, and no distributed transaction/global epoch ([ADR 0129](./decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md)).
- The superseded equipment-history record must remain traceable to [ADR 0130](./decisions/adr-0130-historical-equipment-body-layout-authority.md) while all active behavior and proof resolve to [ADR 0127](./decisions/adr-0127-game-authored-equipment-layouts-with-fail-closed-publication.md).

---

## Related Documentation

- [CI/CD Pipeline](./system-architecture-cicd.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Testing & Continuous Delivery](../product/user-journeys/operators.md#3-testing--continuous-delivery)
