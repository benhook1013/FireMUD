# Platform Operations and Delivery

## Current Status

The lossless source transposition is complete. This tracker consolidates runtime observability, delivery, public-edge policy, operational proof, and recovery tooling by capability; the unchanged source evidence remains the audit backstop while Spark coverage review verifies every allocation.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [02.14 Runtime Identity and Structured Logging Consistency Vertical Slice](../vertical-slices/02.14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice.md) - Shared runtime identity and structured logging | Pre-`06` complete | 1-76 | [source evidence](#source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76) |
| [02.14.1 Gameplay Context Logging Enrichment Vertical Slice](../vertical-slices/02.14.1-task-list-gameplay-context-logging-enrichment-vertical-slice.md) - Gameplay logging enrichment | Implemented | 1-52 | [source evidence](#source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52) |
| [02.14.2 Structured Logging Consistency Audit Vertical Slice](../vertical-slices/02.14.2-task-list-structured-logging-consistency-audit-vertical-slice.md) - Cross-service logging consistency | Implemented | 1-51 | [source evidence](#source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51) |
| [02.14.3 Observability Label Exception Documentation Vertical Slice](../vertical-slices/02.14.3-task-list-observability-label-exception-documentation-vertical-slice.md) - Metrics-label exception policy | Done | 1-34 | [source evidence](#source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34) |
| [Metrics Cardinality and Label Policy Hardening Vertical Slice](../vertical-slices/02.14.4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice.md) - Metrics cardinality policy | implemented at the current policy boundary | 1-92 | [source evidence](#source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92) |
| [Player-Experience Canary and Deadman Smoke Vertical Slice](../vertical-slices/02.14.5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice.md) - Canary, deadman, and smoke evidence | implemented at the current prod-like smoke boundary | 1-83 | [source evidence](#source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83) |
| [Canonical Demo Seed State Hardening Vertical Slice](../vertical-slices/02.14.6-task-list-canonical-demo-seed-state-hardening-vertical-slice.md) - Canonical demo and smoke seed state | implemented at the current smoke/runtime boundary | 1-51 | [source evidence](#source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51) |
| [02.14.6.1 Task List: Smoke Runtime Target Seed Model Isolation Vertical Slice](../vertical-slices/02.14.6.1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice.md) - Runtime-target smoke seeding | complete at the current bounded boundary | 1-71 | [source evidence](#source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71) |
| [Repo-Wide Metrics Policy Convergence and Smoke Evidence Freshness Vertical Slice](../vertical-slices/02.14.7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice.md) - Observability policy and smoke-evidence freshness | implemented | 1-67 | [source evidence](#source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67) |
| [Hosted Preview Manual Proof Vertical Slice](../vertical-slices/02.15-task-list-hosted-preview-manual-proof-vertical-slice.md) - Hosted preview operations and proof | baseline live; real Helm deploy, preview TCP exposure, and hosted smoke are now implemented, while lifecycle/doc-first convergence remains follow-on work | 1-73 | [source evidence](#source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73) |
| [Preview Internal gRPC Transport Alignment Vertical Slice](../vertical-slices/02.15.1-task-list-preview-internal-grpc-transport-alignment-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | completed | 1-46 | [source evidence](#source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46) |
| [Spring gRPC Server TLS Bundle Migration Vertical Slice](../vertical-slices/02.15.2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | complete | 1-70 | [source evidence](#source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70) |
| [Preview Rollout Diagnostics and Fast-Failure Vertical Slice](../vertical-slices/02.15.3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | complete at the current bounded boundary | 1-70 | [source evidence](#source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70) |
| [gRPC Transport Configuration Sanity Checks Vertical Slice](../vertical-slices/02.15.4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | implemented | 1-54 | [source evidence](#source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54) |
| [Preview TCP Admission Cleanup Vertical Slice](../vertical-slices/02.15.5-task-list-preview-tcp-admission-cleanup-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | planned | 1-60 | [source evidence](#source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60) |
| [Fixed Develop Dev-Demo Environment Vertical Slice](../vertical-slices/02.15.6-task-list-fixed-develop-dev-demo-environment-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | implemented | 1-72 | [source evidence](#source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72) |
| [`02.15.7` Gateway Edge Allowlist and Management Contract Convergence](../vertical-slices/02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | implemented for the current explicit public route inventory and owner-side enforcement boundary, with future-route follow-through intentionally tracked in follo | 1-83 | [source evidence](#source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83) |
| [02.15.7.1 Task List: Explicit Session and Admin Edge Route Inventory Vertical Slice](../vertical-slices/02.15.7.1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | complete at the current bounded boundary | 1-98 | [source evidence](#source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98) |
| [Environment Preflight and Secret-Binding Convergence Vertical Slice](../vertical-slices/02.15.8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | partially implemented | 1-71 | [source evidence](#source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71) |
| [`02.15.8.1` External Binding Isolation Preflight Follow-Through](../vertical-slices/02.15.8.1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | complete at the current bounded boundary | 1-78 | [source evidence](#source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78) |
| [`02.15.8.2` Service-Discovery Override Preflight Enforcement Follow-Through](../vertical-slices/02.15.8.2-task-list-service-discovery-override-preflight-enforcement-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | complete | 1-77 | [source evidence](#source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77) |
| [Kubernetes Deployment-Contract Convergence Vertical Slice](../vertical-slices/02.15.9-task-list-kubernetes-deployment-contract-convergence-vertical-slice.md) - Hosted preview, transport, deployment, or preflight operations | completed | 1-37 | [source evidence](#source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37) |
| [Local Reset and Bootstrap Proof Tooling Vertical Slice](../vertical-slices/02.17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice.md) - Local reset and bootstrap proof tooling | complete through `02.17.1` to `02.17.3` | 1-71 | [source evidence](#source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71) |
| [Service DB Reset and Flyway Hygiene Vertical Slice Task List](../vertical-slices/02.17.1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice.md) - Database reset and Flyway hygiene tooling | implemented | 1-34 | [source evidence](#source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34) |
| [Fresh Bootstrap and Restart Proof Tooling Vertical Slice Task List](../vertical-slices/02.17.2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice.md) - Fresh-bootstrap and restart proof tooling | implemented | 1-40 | [source evidence](#source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40) |
| [Pre-v1 Migration Squash and Baseline Reset Vertical Slice](../vertical-slices/02.17.2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice.md) - Pre-v1 migration baseline reset | complete at the current target boundary | 1-69 | [source evidence](#source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69) |
| [Flyway Migration Sanity Checks Vertical Slice Task List](../vertical-slices/02.17.3-task-list-flyway-migration-sanity-checks-vertical-slice.md) - Flyway migration sanity tooling | implemented | 1-35 | [source evidence](#source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35) |
| [Local Gradle Proof Reliability Vertical Slice](../vertical-slices/02.17.4-task-list-local-gradle-proof-reliability-vertical-slice.md) - Local Gradle proof reliability | complete at the current operator-proof boundary | 1-83 | [source evidence](#source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83) |
| [Fresh Bootstrap Image Freshness and Smoke Transport Convergence Vertical Slice](../vertical-slices/02.17.5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice.md) - Fresh-image and smoke transport proof | complete at the current bounded boundary | 1-98 | [source evidence](#source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98) |
| [`02.18.13` Runtime Feature Flag Authority Convergence](../vertical-slices/02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md) - Operator ingress and audit-surface responsibilities | complete | 3, 7, 20-21, 33-34, 40 | [source evidence](#source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-3-7-20-21-33-34-40) |
| [`02.18.14` Moderation Policy Definition and Enforcement Split](../vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md) - Logging and Admin policy-control ingress | complete | 19-23, 35-38 | [source evidence](#source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-19-23-35-38) |
| [Cross-Service Test Fixtures and Shutdown Noise Vertical Slice](../vertical-slices/02.18.16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice.md) - Cross-service test fixtures and shutdown proof | complete | 1-32 | [source evidence](#source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32) |
| [Canonical Redis Runbook Sequence Vertical Slice](../vertical-slices/02.18.17-task-list-canonical-redis-runbook-sequence-vertical-slice.md) - Canonical Redis operations runbook | complete | 1-30 | [source evidence](#source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30) |
| [Gameplay Transport Test Harness Convergence Vertical Slice](../vertical-slices/02.18.17-task-list-gameplay-transport-test-harness-convergence-vertical-slice.md) - Gameplay transport test harness | complete at the current boundary | 1-164 | [source evidence](#source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164) |
| [Gameplay Proof and Cross-Service Fixture Convergence Vertical Slice](../vertical-slices/02.18.18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice.md) - Gameplay proof and fixture convergence | complete; the narrower transcript/baseline hardening follow-up in `02.18.18.1` is now also complete at its current bounded boundary | 1-79 | [source evidence](#source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79) |
| [Gameplay Transcript and Baseline Proof Hardening Vertical Slice](../vertical-slices/02.18.18.1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice.md) - Gameplay transcript proof hardening | complete at the current bounded boundary | 1-85 | [source evidence](#source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85) |
| [Flyway History Contract and Hosted SQL Proof Cleanup Vertical Slice](../vertical-slices/02.19.12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice.md) - Flyway and hosted SQL operational proof | implemented | 1-63 | [source evidence](#source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63) |
| [Basic Multiplayer Load Proof Vertical Slice Task List](../vertical-slices/05.1-task-list-basic-multiplayer-load-proof-vertical-slice.md) - Multiplayer load proof | implementation-complete for the bounded first-pass proof. Deterministic cross-service proof is now live in `game-session-service`: 10 concurrent WebSocket clien | 1-137 | [source evidence](#source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137) |
| [08.3.1 Task List: Operator Cutover-Compatibility Readback Vertical Slice](../vertical-slices/08.3.1-task-list-operator-cutover-compatibility-readback-vertical-slice.md) - Audited primary runtime or service owner | complete at the current bounded boundary | 1-87 | [source evidence](#source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87) |

## Canonical Design Sources

- [Logging and monitoring](../../architecture/system-architecture-logging-monitoring.md) and [tracing](../../architecture/system-architecture-tracing.md) define runtime identity, correlation, and bounded metrics policy.
- [Testing](../../architecture/system-architecture-testing.md), the [player-experience incident runbook](../../architecture/system-architecture-player-experience-incident-runbook.md), and [backup/recovery evidence](../../architecture/system-architecture-backup-recovery-evidence-and-compliance.md) define operational player-facing proof.
- [Deployment environments](../../architecture/infrastructure/deployment-environments.md), the [deployment runbook](../../architecture/system-architecture-deployment-runbook.md), and [CI/CD](../../architecture/system-architecture-cicd.md) define environment delivery.
- [Gateway](../../architecture/system-architecture-gateway.md), the [authorization route matrix](../../architecture/system-architecture-authz-route-matrix.md), [gRPC](../../architecture/system-architecture-grpc.md), and [protocol bridging](../../architecture/system-architecture-protocol-bridging.md) define edge and transport policy.
- [Deploy preflight policy](../../architecture/system-architecture-deploy-preflight-policy.md), the [environment/secrets catalog](../../architecture/infrastructure/environment-and-secrets-catalog.md), and [JWT/token contracts](../../architecture/system-architecture-jwt-and-token-contracts.md) define deployment binding and credential rules.

## Consolidated Implementation Record

### Runtime Identity, Logging, and Metrics Discipline

`common-platform-core` supplies one runtime `serviceInstanceId`; startup, HTTP, gRPC, WebSocket, and Telnet paths carry stable service, instance, trace, and correlation context. `/actuator/runtime` exposes runtime identity and build metadata. Game Session and Game Logic add gameplay context when it is already known rather than inventing scope data for logs.

Prometheus metrics use bounded semantic labels. Raw tenant, session, player, version, and per-instance identifiers are prohibited as labels, and a new bounded-scope exception requires an architecture update rather than a local workaround. `regionId` logging remains deferred until an authoritative current-region source exists; broader reactive MDC propagation is optional follow-through, not a silent correctness gap.

Gateway gameplay handshakes now emit a bounded rejection counter keyed by the canonical route, HTTP status, and handshake error class. Gateway-originated client closes are normalized to the bounded close and subreason taxonomy, logged with those fields, and counted through `gateway.websocket.closes`; outbound bridge-buffer pressure closes with `policy_violation/edge_backpressure` and increments the dedicated slow-client subset meter. This preserves an operable client-close contract without exposing arbitrary upstream close text as a metric label.

### Player-Experience Smoke, Canaries, and Evidence

The canonical smoke runner proves public bootstrap, connect-token admission, WebSocket `LOGIN -> PLAY -> LOOK`, Telnet `WORLDS`, and the current real-stack transport path. It uses a synthetic non-player identity per environment, emits canary metrics, supports independent entry probes and failure injection, validates retained evidence freshness, and feeds alert/deadman behavior. `LOOK` is the first gameplay command canary.

Deadman authority remains external to Prometheus or in-cluster evaluation. First-live or reopen proof still requires current retained evidence produced by operators or automation; the implementation provides the mechanism and validation, not fictional evidence for an environment that has not been exercised.

### Hosted Preview and Environment Delivery

Hosted preview deploys real Helm releases, exposes preview TCP, supports hosted smoke, carries fixed develop dev-demo support, and has rollout diagnostics, internal gRPC transport alignment, TLS bundle handling, and NetworkPolicy parity. Player-facing staging/production Kustomize remains canonical; preview is a prod-like environment, not a separate gameplay architecture. TCP-first manual proof precedes browser UX proof.

The remaining preview work is clean-redeploy lifecycle and documentation convergence. Preview TCP admission still has temporary bootstrap glue scheduled for deliberate cleanup, rather than a hidden permanent exception to the public admission model.

### Curated Public Edge and Transport Contracts

Gateway is an explicit public edge, not permissive service fan-out. Public routes have an allowlisted inventory, internal subtrees are blocked, and session/admin authorization converges at the owning service. `/api/session/**`, `/api/admin/**`, `/assets/**`, WebSocket, Telnet, and `gateway.v1` management routes remain explicit contracts. gRPC transport/TLS configuration is validated against the deployment contract.

Future public routes must be added through the same inventory and owner-side enforcement model. No new edge route may rely on broad proxy exposure or Gateway-local replication of service authorization policy.

### Preflight, Secrets, and Deployment Bindings

Expected-binding manifests drive deployment preflight. Required policy ids, `expectedBindingsRef`, exact Secret and image-pull bindings, canonical binding-reference syntax, external-binding isolation, and explicit service-discovery overrides are validated before deploy. JWT signing/JWKS material mounts through the declared binding model, and preview renders unique credential material rather than accidentally reusing an unrelated environment.

Environment manifests own binding identity. External bindings are unique by default and shared only with matching rationale. The current proof is stronger configuration and rendered-manifest validation; richer Kubernetes live-state validation, traffic-open evidence, and automated JWT/JWKS rotation are still separate operational work.

### Reset, Bootstrap, Persistence, and Recovery Tooling

Service-scoped database reset, Flyway hygiene and history checks, image-freshness controls, fresh-bootstrap/restart proof, locked Gradle validation, and repair-oriented demo/runtime seeders are implemented. The canonical Redis coordination reset/recovery sequence is the only normative reset path. Fresh proof scripts rebuild/boot current artifacts rather than relying on stale containers or images.

These tools provide the bounded reset and recovery foundation. Future smoke/bootstrap improvements should extend the canonical scripts and runbooks rather than adding ad hoc Compose loops or service-specific reset folklore.

### Gameplay Proof, Operator Control, and Scale Boundary

Shared WebSocket/Telnet gameplay drivers, cross-service fixtures, fresh baselines, transcript-block assertions, Account runtime fakes, and bounded ten-player concurrency proof are live. Game Session owns persisted runtime feature-flag truth; Logging & Admin is privileged ingress/audit and forwards to the owner. Moderation policy definition and runtime enforcement are similarly split by owner, and cutover compatibility readback consumes the canonical Game Session result.

The current scale claim is intentionally bounded. Higher-volume load/soak, combat, inventory, and broad fanout proof remain future work. New gameplay suites should reuse the shared reconnect/takeover and transport helpers rather than rebuilding fragile local fixtures.

## Active Gaps

- Preview clean-redeploy lifecycle/doc convergence and the planned preview TCP admission cleanup remain open.
- Deployment preflight does not yet replace richer live-cluster validation, real traffic-open evidence, or automated JWT/JWKS rotation proof.
- Runtime logging awaits a canonical current-region source before attaching `regionId` broadly; any expanded reactive MDC work remains optional and bounded.
- Player-experience evidence must still be produced and retained for actual first-live/reopen environments; freshness validation cannot create a real proof run by itself.
- High-volume load, soak, combat, inventory, and broad-fanout proof remain beyond the current ten-player concurrency boundary.

## To Discuss

No competing target state is currently recorded for bounded metrics, curated edge routing, external deadman authority, manifest-owned deployment binding, or canonical reset tooling. Future design discussion is required before allowing a metrics-label exception, adding a public route class, creating an environment-specific transport exception, or defining credential rotation automation. The source evidence preserves detailed operational rationale and completed sub-slices.

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| Common Platform Core | Runtime identity, shared logging/metrics policy | Shared observability components and `/actuator/runtime` |
| Gateway, TCP Proxy, Account, Game Session | Public admission, transport bridging, smoke-visible gameplay entry | HTTP, WebSocket, Telnet, and internal gRPC contracts |
| Helm, Kustomize, CI workflows | Preview/dev-demo/staging/prod delivery and rendered deployment policy | Helm releases, Kustomize environments, GitHub Actions |
| Deploy preflight tooling | Binding, secret, discovery, and policy validation | `dev-tools/deploy/preflight.py` and manifest contracts |
| PostgreSQL, Flyway, Redis, Docker tooling | Reset, migration, bootstrap, and recovery proof | Canonical scripts, migrations, Redis reset runbook |
| Logging & Admin | Privileged operator ingress, audit, remediation/readback projection | REST/OpenAPI over owner-side runtime contracts |
| External monitor and observability tooling | Deadman authority, canary evidence, alerts, retained proof | Smoke/canary metrics and external liveness signal |

Focused observability, smoke, preview, preflight, deployment, reset, fixture, and scale-proof commands remain recorded in the source evidence. Spark coverage review will verify the consolidated statements against each allocated range before this tracker is marked fully reviewed.

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76

#### 02.14 Runtime Identity and Structured Logging Consistency Vertical Slice - Shared runtime identity and structured logging (source lines 1-76)

##### Preserved Source Text: source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76

<!-- migration-source path="design/project-management/vertical-slices/02.14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice.md" lines="1-76" sha256="8f7bdd96aedf9a69aaf91c4ef5418370a3ad7efff9177e91721da6205e25a102" heading-offset="3" -->
#### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: 02.14 Runtime Identity and Structured Logging Consistency Vertical Slice

Status: Pre-`06` complete

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Implementation Notes

- Shared runtime identity is now live in `common-platform-core`.
- Shared startup lifecycle logging is live.
- Baseline HTTP and gRPC request-path logging now includes `service`, `serviceInstanceId`, `traceId`, and `correlationId`.
- A lightweight runtime identity surface is live at `/actuator/runtime` for services that include the shared web stack.
- `02.14.1` gameplay-context logging enrichment is now implemented for the highest-value Game Session and Game Logic paths where gameplay identity is already present in the active request or session context.
- Follow-up work is now explicitly split into:
  - `02.14.2` structured logging consistency audit
  - `02.14.3` observability label exception documentation
- The pre-`06` baseline is now complete:
  - runtime identity is shared and exposed;
  - startup/request/gameplay-path structured logging is in place;
  - gameplay-context enrichment and the first consistency audit have landed;
  - and metric-label exceptions are explicitly documented as not approved.

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Goal

Add one canonical runtime identity model for running service instances and make structured logging consistency real enough that operators can reliably answer:

- which logical service emitted this log,
- which running instance emitted it,
- which request or gameplay flow it belongs to,
- and which build/restart they are looking at.

This slice exists to harden shared infrastructure behavior before more gameplay systems land on top of it.

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Scope

- Define and implement a shared `serviceInstanceId` abstraction in `common-platform-core`.
- Ensure `serviceInstanceId` represents one running instance, not static operator config.
- Keep `service` as the logical service identity (`spring.application.name`).
- Emit one structured startup lifecycle log per service with runtime identity and build metadata when available.
- Standardize baseline structured log fields for request- and gameplay-path logs.
- Expose runtime identity through a lightweight runtime-info/admin-debug surface.
- Verify actual JSON logging consistency against the existing logging/monitoring design rather than relying only on docs.

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Canonical Runtime Identity Rules

- `service` is the logical service name and remains stable across replicas.
- `serviceInstanceId` is unique to one running instance.
- Prefer true runtime identity from the platform when available.
- Otherwise generate a unique startup-time identifier.
- Do not treat `serviceInstanceId` as a static manually assigned config value by default.
- Optional extra fields may include `hostname`, `buildSha`, `buildVersion`, `imageTag`, and `bootedAt`.

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Tasks

- [x] Add a shared runtime identity component in `common-platform-core`.
- [x] Define the common precedence for deriving `serviceInstanceId` from runtime environment or startup generation.
- [x] Add a shared structured startup lifecycle log/event contract.
- [x] Ensure baseline request-path logs always include:
  - `service`
  - `serviceInstanceId`
  - `traceId`
  - `correlationId`
- [x] Ensure gameplay identity fields (`tenantId`, `regionId`, `gameInstanceId`, `characterId`) are attached where known and practical.
- [x] Expose runtime identity and build metadata via a lightweight runtime-info surface.
- [x] Audit services for actual JSON logging consistency and close any gaps between implementation and `system-architecture-logging-monitoring.md`.
- [x] Keep `serviceInstanceId` out of most Prometheus labels by default; document any narrow exceptions explicitly.
- [x] Add focused tests for the shared runtime identity component and any startup-log/info contributor behavior.

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Out of Scope

- [ ] Per-instance metric labeling across the platform.
- [ ] Full observability-stack redesign.
- [ ] Broad alert taxonomy changes unrelated to runtime identity or structured log consistency.

##### source-02-14-task-list-runtime-identity-and-structured-logging-consistency-vertical-slice-1-76: Validation

- [x] `./gradlew check`
- [x] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52

#### 02.14.1 Gameplay Context Logging Enrichment Vertical Slice - Gameplay logging enrichment (source lines 1-52)

##### Preserved Source Text: source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52

<!-- migration-source path="design/project-management/vertical-slices/02.14.1-task-list-gameplay-context-logging-enrichment-vertical-slice.md" lines="1-52" sha256="fa5e20773419e09c8402e49ed8c7348682f8e7cecc245a2738ff892ac00ee87b" heading-offset="3" -->
#### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: 02.14.1 Gameplay Context Logging Enrichment Vertical Slice

Status: Implemented

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Implementation Notes

- `game-session-service` now enriches the highest-value admission and gameplay command paths with gameplay identity fields using a shared `GameplayLoggingContext` helper.
- The first rollout covers `PLAY`, `LOOK`, movement, and communication command handling, which already know `tenantId`, `gameInstanceId`, and `characterId`.
- Reconnect restore and recipient-delivery paths now also reuse the same gameplay logging context when a bound session or recipient context is already available.
- Tick enqueue/query/process and TCP disconnect coordination logs in `game-session-service` now also reuse the same bounded gameplay logging context when session or request identity is already known.
- `game-logic-service` LOOK, MOVE, and communication aggregation paths now attach the same gameplay identity field set for their high-value debug/warn logs when the gRPC request already carries it.
- `regionId` remains deferred until those command paths have a canonical current-region source without widening the slice.

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Goal

Carry the `02.14` structured logging baseline into the highest-value gameplay and session paths so logs include gameplay identity fields when they are actually known, not just request-correlation and runtime identity.

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Scope

- Add `tenantId`, `regionId`, `gameInstanceId`, and `characterId` to high-value gameplay/session logs where that context is already available.
- Focus first on the command, admission, reconnect, and communication paths where operator debugging needs this context most.
- Keep the work bounded to shared logging enrichment and obvious local call-path improvements.

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Tasks

- [x] Identify the highest-value gameplay/session log paths that already know gameplay identity.
- [x] Enrich those logs so they attach `tenantId`, `regionId`, `gameInstanceId`, and `characterId` when available.
- [x] Preserve the `02.14` baseline fields:
  - `service`
  - `serviceInstanceId`
  - `traceId`
  - `correlationId`
- [x] Add focused tests where logging context is assembled in shared helpers or well-bounded service paths.
- [x] Update any relevant architecture or service docs if the canonical field set changes.

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Result

- The highest-value Game Session admission and gameplay command paths now attach `tenantId`, `gameInstanceId`, and `characterId` through a shared helper rather than ad hoc local MDC handling.
- The same bounded gameplay-context enrichment now also covers reconnect replay/refresh, tick/disconnect session coordination, live communication recipient delivery, and high-value Game Logic LOOK/MOVE/communication service logs where the bound request context is already known.
- The canonical architecture contract remains unchanged: `regionId` belongs in the same field set when known.
- `regionId` stays deferred because the current command/session paths do not yet have one canonical current-region source that can be attached without widening the slice.

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Out of Scope

- [ ] Logging enrichment for every low-value debug log in the repo.
- [ ] Full JSON logging consistency audit across all services.
- [ ] Per-instance metrics labeling.

##### source-02-14-1-task-list-gameplay-context-logging-enrichment-vertical-slice-1-52: Validation

- [ ] `./gradlew check`
- [ ] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51

#### 02.14.2 Structured Logging Consistency Audit Vertical Slice - Cross-service logging consistency (source lines 1-51)

##### Preserved Source Text: source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51

<!-- migration-source path="design/project-management/vertical-slices/02.14.2-task-list-structured-logging-consistency-audit-vertical-slice.md" lines="1-51" sha256="e9df16d16d95f45e0e8c4ff2fad2331e9c631872a0958670077cce3864ff6716" heading-offset="3" -->
#### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: 02.14.2 Structured Logging Consistency Audit Vertical Slice

Status: Implemented

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Implementation Notes

- The first audit pass confirmed that the shared servlet and gRPC logging paths already carry the `02.14` baseline cleanly.
- The highest-value remaining drift was in custom WebSocket edge handlers, which bypass the shared HTTP and gRPC logging filters.
- `game-session-service` and `spring-cloud-gateway` now use a shared `RuntimeLoggingContext` helper on those WebSocket paths so runtime identity and stable connection correlation fields are present for local handler logs too.
- `tcp-proxy-service` Telnet edge-handler logs now also normalize through the same runtime logging context helper, so the main player-facing edge paths all carry the shared runtime identity and stable connection-correlation baseline.
- A follow-up audit pass closed the remaining practical pre-06 drift in `spring-cloud-gateway` and `tcp-proxy-service` custom WebSocket paths:
  - Gateway gameplay-handshake rejection logs now open a bounded runtime logging context so local rejection logs keep `service`, `serviceInstanceId`, and a stable request/transport correlation id.
  - Gateway gameplay bridge stall-path logs now reuse the downstream connection correlation id instead of emitting runtime-only context.
  - Development echo WebSocket handlers in Gateway and TCP Proxy now use the same bounded runtime logging context so their local logs no longer bypass the platform field baseline entirely.
- Full reactive-request MDC propagation outside the shared filters remains follow-up work if later debugging shows the need; this slice stays intentionally bounded to the biggest practical gaps.

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Goal

Verify that the shared `02.14` runtime identity and request-correlation fields actually appear consistently in real service logs, and close the biggest gaps where custom logging paths still drift away from the platform contract.

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Scope

- Audit the major service logging paths for consistency with `system-architecture-logging-monitoring.md`.
- Identify custom logger paths or service-specific request handling that bypass the shared baseline.
- Fix the highest-value gaps rather than attempting a total observability rewrite.

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Tasks

- [x] Audit HTTP request logging across servlet and reactive services for consistent field presence.
- [x] Audit gRPC server-side logging paths for the same baseline field set.
- [x] Identify custom service-local logging paths that should be normalized or documented as exceptions.
- [x] Fix the most important consistency gaps discovered during the audit.
- [x] Update design docs if the real canonical contract needs clarification after the audit.

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Result

- The canonical logging contract is now explicit for the main request paths: shared servlet HTTP, shared reactive HTTP, shared gRPC, custom WebSocket edge handlers, and Telnet edge-handler logs normalized through `RuntimeLoggingContext`.
- The remaining pre-06 custom Gateway and TCP Proxy WebSocket logs now consistently carry the shared runtime identity baseline rather than mixing in handler-local logs with missing correlation or instance fields.
- The audit remains intentionally bounded rather than becoming an observability rewrite.
- Broader reactive-request MDC propagation outside the shared filters remains the only notable post-`02.14.2` follow-up area instead of hidden unfinished scope.

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Out of Scope

- [ ] Replacing all service-local log messages with a new logging framework.
- [ ] Vendor-specific log-pipeline work.
- [ ] Broad alerting or tracing redesign.

##### source-02-14-2-task-list-structured-logging-consistency-audit-vertical-slice-1-51: Validation

- [x] `./gradlew check`
- [x] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34

#### 02.14.3 Observability Label Exception Documentation Vertical Slice - Metrics-label exception policy (source lines 1-34)

##### Preserved Source Text: source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34

<!-- migration-source path="design/project-management/vertical-slices/02.14.3-task-list-observability-label-exception-documentation-vertical-slice.md" lines="1-34" sha256="0ee20326f876c8ff641ef4c9e53a51936c0fcfa2e42ff38c9ad2c334523f20fc" heading-offset="3" -->
#### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: 02.14.3 Observability Label Exception Documentation Vertical Slice

Status: Done

##### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: Implementation Notes

- The current canonical policy is explicit: no Prometheus metrics in the standard FireMUD contract currently carry `serviceInstanceId` or other per-instance runtime identity labels.
- The architecture doc now also states that any future exception must be documented there before it becomes part of the canonical metrics set.

##### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: Goal

Keep the `02.14` rule against per-instance metrics cardinality honest by documenting any narrow exceptions explicitly instead of letting them appear ad hoc in implementation.

##### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: Scope

- Review whether any diagnostic metrics truly need `serviceInstanceId` or similar high-cardinality runtime identity labels.
- Document approved exceptions explicitly if they exist.
- Otherwise keep the documented answer simple: no standard metrics should carry `serviceInstanceId`.

##### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: Tasks

- [x] Review existing metrics for any real runtime-identity label exceptions.
- [x] Document approved narrow exceptions explicitly if they exist.
- [x] Otherwise record the explicit “no exceptions currently approved” state in the relevant design docs.
- [x] Update the `02.14` area docs so future metrics work has one clear policy to follow.

##### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: Out of Scope

- [ ] Adding new per-instance metric labels speculatively.
- [ ] Reworking the platform metrics model.

##### source-02-14-3-task-list-observability-label-exception-documentation-vertical-slice-1-34: Validation

- [x] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92

#### Metrics Cardinality and Label Policy Hardening Vertical Slice - Metrics cardinality policy (source lines 1-92)

##### Preserved Source Text: source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92

<!-- migration-source path="design/project-management/vertical-slices/02.14.4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice.md" lines="1-92" sha256="b9e56f41484f9921c3c4edef85eb7a96506b03e39c066f6b30ca6f012c06b830" heading-offset="3" -->
#### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Metrics Cardinality and Label Policy Hardening Vertical Slice

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Goal and Status

Goal: tighten the canonical metrics-label policy so gameplay and session instrumentation does not drift into high-cardinality labels such as raw tenant ids, patch versions, or other unbounded identifiers. Status: implemented at the current policy boundary.

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Why This Slice Exists

The repo already has an observability discipline around structured logging and some metric-label limits, but current gameplay/session metrics are starting to drift:

- some counters tag by raw `tenantId`;
- some session metrics tag by raw `script_patch_version`;
- those choices are manageable in development but risky in long-lived prod-like environments.

This deserves a dedicated follow-up before the metrics surface grows further.

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Implementation Notes

Implemented in the first pass:

- gameplay command invocation/failure counters in `game-session-service` no longer tag by raw `tenantId`;
- session resume/takeover/fresh-entry-fallback counters no longer tag by raw `tenantId`;
- `game_sessions_started_total` no longer tags by raw `script_patch_version`.
- the gameplay retry-queue depth metric no longer creates one gauge per `(tenantId, queueTargetId)` pair; it is now exposed as aggregate queue-depth gauges instead.
- the `tcpproxy.bridge.shutdown` metric now uses a bounded semantic `classification` label instead of the generic `class` label shape.
- CI now runs a static cardinality-policy check that rejects obvious forbidden label keys such as raw `tenantId`, `sessionId`, `characterId`, `script_patch_version`, and generic `class` in metric-building code.
- that guardrail now also rejects new literal metric labels that follow raw identifier patterns such as `*Id` or `*_id`, so future drift is blocked even when the exact key name was not already on a short denylist.
- a follow-up manual audit of the main gameplay/runtime hot paths found the remaining live labels in those areas are bounded semantic tags rather than unbounded identifiers:
  - `CommunicationCommandHandler`, `LookCommandHandler`, `MoveCommandHandler`, and `PlayCommandHandler` use bounded `type`, `error`, or `reason` tags;
  - `TcpProxyEventService` and `TelnetServerHandler` use untagged counters/timers or the bounded `classification` tag on shutdown metrics;
  - `GrpcAppErrors` uses normalized application error codes on the shared `grpc.app_error` metric.
- a broader follow-up audit outside those gameplay/runtime hot paths also found the reviewed remaining metric surfaces are still bounded or untagged:
  - the former local echo WebSocket handler used a constant endpoint tag rather than dynamic endpoint identity;
  - `DatabaseAutoConfiguration` uses untagged liveness/configuration gauges such as `redis.up`;
  - the remaining cache, queue, saga, and world/entity counters reviewed in service code are currently untagged totals rather than hidden per-tenant or per-entity series.
- a further audit pass across platform-health and scheduler surfaces did not find new live cardinality drift:
  - `ReadinessTransitionTracker` tags `component`, `to_status`, and `failing_dependency`, but current callers pass bounded service/component identifiers and bounded dependency keys such as `accountService`, `gameLogicService`, or `gameplayRoute` rather than raw hostnames, tenant ids, or request paths;
  - `TickScheduler` queue-depth and pending-session gauges are aggregate process gauges, and its internal `(tenantId, sessionId)` key is only a local set membership key rather than a metric label;
  - automation scripting, world-management, and entity-management runtime counters reviewed in this pass remain untagged totals or use bounded semantic labels only.
- another follow-up pass across account, logging-admin, social-groups, world-management, and game-design service code likewise did not find fresh cardinality drift:
  - the reviewed world-management counters for room cache and world-creation/event paths are untagged totals only;
  - the reviewed social-groups chat publishing/error counters are untagged totals only;
  - the reviewed account and logging-admin service code did not surface additional custom tagged metrics in the audited paths.
- the static guardrail now also scans the canonical observability docs for metric examples that would reintroduce forbidden raw identifier labels such as `tenantId` or `script_patch_version`, so the repo stops teaching a different policy than the service code enforces.
- a later runtime follow-up pass removed fresh drift that had reappeared in tick observability:
  - the remote follow-up drain gauges/counter no longer use raw `tenantId` / `regionId` Prometheus labels;
  - those metrics are now aggregate process signals only, while per-region drilldown remains on durable runtime ownership/control-plane reads.
- an additional pass across shared gRPC, delivery, proxy, and aggregation surfaces also remained within bounded label shapes:
  - `MetricsInterceptor` uses `method` and transport `status` tags derived from fixed gRPC service/method descriptors rather than request identity;
  - `CommunicationRecipientDeliveryService` tags delivery counters only by bounded recipient `role`;
  - `TelnetServerHandler` and related disconnect/shutdown metrics use bounded semantic tags such as disconnect status, app error code, and shutdown classification rather than session or tenant identity;
  - `CommunicationAggregationService` continues to route application failures through normalized `grpc.app_error` codes rather than adding new dynamic communication labels.
- one more follow-up spot-check across the remaining likely tail surfaces did not reveal fresh drift:
  - `ReadinessTransitionTracker` still uses bounded `component`, `to_status`, and `failing_dependency` values sourced from fixed service/dependency identifiers rather than raw hostnames or tenant/session identity;
  - the former local echo WebSocket handler used a fixed endpoint tag rather than dynamic route/session identity;
  - `TcpProxyEventService` still uses only bounded `type=connect|disconnect` tags plus untagged timers;
  - `MetricsInterceptor` still tags only fixed gRPC method descriptors and transport status values.

This slice is now closed at its intended audit boundary:

- the static guardrail exists and blocks the obvious forbidden label patterns in service metric-building code;
- the main gameplay/runtime, scheduler, proxy, gRPC, and neighboring service surfaces were re-audited without finding fresh live cardinality drift;
- the canonical architecture wording now treats raw tenant/session/player/script identifiers as log/trace/audit correlation keys rather than ordinary Prometheus labels;
- no bounded tenant bucketing exception is approved in the canonical metrics contract today. Future metrics that truly need scoped SLO drilldown must introduce an explicitly bounded `scope` label through a fresh architecture update rather than reintroducing raw identifier labels by default.

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Scope

- Define which labels are acceptable on canonical Prometheus metrics.
- Define which identifiers must not be used as ordinary metric labels.
- Audit the current gameplay/session metrics against that policy.
- Define the alternative patterns for high-cardinality diagnostics:
  - structured logs;
  - tracing/span tags;
  - bounded sampling/debug metrics.

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Out of Scope

- Redesigning the entire observability stack.
- Removing useful structured logging context.

##### source-02-14-4-task-list-metrics-cardinality-and-label-policy-hardening-vertical-slice-1-92: Locked Direction

- raw `tenantId` must not appear on ordinary canonical metrics;
- version-like identifiers such as `script_patch_version` must not appear as ordinary metric labels;
- high-cardinality identifiers belong in structured logs and tracing, not default counters/timers;
- low-cardinality labels such as command type, bounded error code, and reason tags remain acceptable.
<!-- /migration-source -->

### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83

#### Player-Experience Canary and Deadman Smoke Vertical Slice - Canary, deadman, and smoke evidence (source lines 1-83)

##### Preserved Source Text: source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/02.14.5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice.md" lines="1-83" sha256="eee0e94d7ecf395ae0b6d22fba2eb70a1ba703be74c2d6f765eca8ceedf86354" heading-offset="3" -->
#### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Player-Experience Canary and Deadman Smoke Vertical Slice

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Goal and Status

Goal: make the prod-like observability contract executable by adding canonical player-flow canaries, independent entry-path blackbox/deadman signals, alert rules, and retained smoke evidence for the player-facing paths documented in the logging, monitoring, and testing architecture. Status: implemented at the current prod-like smoke boundary.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Implementation Notes

The first alerting substrate is now live:

- shared player-experience alert snippets and the canonical Kubernetes `PrometheusRule` now include the required `PlayerFlowCanaryLoginFailed`, `PlayerFlowCanaryCommandFailed`, and `PlayerFlowCanaryLatencyHigh` alert families over the bounded `playerflow_canary_*{flow,path,target}` metric shape;
- the shared observability-stack alert snippets and canonical Kubernetes `PrometheusRule` now include `ObservabilityDeadmanHeartbeatStale` over the mirrored `observability_deadman_heartbeat_timestamp_seconds{source}` signal with the documented `3 * 60s` freshness threshold;
- the observability contract validator now requires the player-flow canary alerts, existing WebSocket/Telnet blackbox alerts, and deadman staleness alert so shared alert assets cannot drift away from the prod-like smoke contract silently.
- `dev-tools/observability/validate-player-experience-smoke-evidence.py` now validates retained prod-like smoke evidence for operator accountability, external deadman/entrypoint checks, mirrored WebSocket/Telnet blackbox signals, mirrored login/command canary success, command canary latency, and required canary-alert exercise results.

The runtime producer side is now live in the repo's canonical operator-run harness:

- `dev-tools/observability/run-player-experience-smoke.py` runs the canonical player-flow canary, independent WebSocket/Telnet blackbox probes, mirrored deadman heartbeat emission, retained evidence generation, and optional OpenMetrics-style mirrored signal output;
- the WebSocket path now proves the real first-party public handshake rather than hidden internal headers: `POST /auth/player-bootstrap`, bootstrap-backed world/realm/character discovery, `POST /auth/connect-token`, gateway `Firemud-Connect-Token` cookie admission, then `LOGIN` plus `PLAY`;
- the same public path now proves through the canonical gateway route family rather than a direct Account Service side door; gateway request rate limiting resolves anonymous bootstrap calls by canonical client-IP key so `POST /api/account/auth/player-bootstrap` and `POST /api/account/auth/connect-token` remain usable at the public edge;
- the Telnet blackbox path now proves a real `WORLDS` handshake rather than treating plain TCP port-open as sufficient player-path evidence;
- the harness reuses the shared gameplay smoke helpers in `dev-tools/smoke/smoke_common.py` so the canonical gameplay/login flow stays aligned with the existing WebSocket and Telnet smoke drivers instead of becoming a parallel one-off script;
- controlled non-production failure injection is supported through the harness so canary, blackbox, deadman, and external-authority alert paths can be exercised without sending production pages;
- `dev-tools/tests/player-experience-smoke-runner-contract.sh` and `dev-tools/observability/validate-player-experience-smoke-evidence.py` now provide a stable contract check for retained evidence and mirrored metric output.
- retained evidence no longer synthesizes a fake “incident opened” boolean; it now records the authoritative deadman and observability-entrypoint check objects, each with explicit `evidenceRef`, `target`, and `checkRef` fields so operators can tie the smoke record back to the external pager/deadman product and the authoritative external monitor target identity.
- the canonical runner no longer treats env-fed `green/red` strings as sufficient authority for prod-like smoke; non-simulated runs must provide `--external-authority-evidence` (or `PLAYER_EXPERIENCE_EXTERNAL_AUTHORITY_EVIDENCE`) pointing at a retained authoritative check record, while `--simulate` remains the only mode allowed to synthesize external-authority state for contract proof.

The previously open operator decisions are now locked for the first implementation cut:

- use one dedicated synthetic non-player identity per prod-like environment;
- run one canonical login canary plus one representative post-admission command canary, with `LOOK` as the first command target;
- run independent WebSocket and Telnet/TCP blackbox probes as separate entry-path checks rather than trying to overload the player canary to serve both purposes;
- mirror a deadman heartbeat through an authority outside Prometheus or in-cluster alert evaluation so total observability failure can still be detected;
- keep failure injection limited to non-production environments and route it so production paging destinations are never exercised by ordinary canary proof.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Why This Slice Exists

The architecture now explicitly requires prod-like environments to prove more than service-local metrics and static dashboard linting. Live-traffic SLIs are not enough for low-traffic environments, and in-cluster Prometheus alerts are not enough to prove total entry-path or monitoring-stack failure. The current repo has static observability contract checks and entry-path alert examples, but the full external canary/deadman proof is still target-state work.

In this slice, a "deadman" signal means a heartbeat whose absence is itself an alert condition. The purpose is to detect failure of the monitoring/checking path even when no ordinary request-level failure is being reported.

Without a slice, this requirement can stay buried in `system-architecture-logging-monitoring.md` and `system-architecture-testing.md` as prose even though it affects deployment readiness, traffic-open evidence, and operator trust.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Scope

- Define the canonical synthetic player-flow canary runner shape for login and one representative command path across the supported public entry paths.
- Emit low-cardinality mirrored metrics such as `playerflow_canary_success{flow,path,target}` and `playerflow_canary_latency_ms{flow,path,target}` without account, tenant, player, character, or trace identifiers as labels.
- Add or wire independent entry-path blackbox probe signals such as `entrypath_blackbox_probe_success{path,target}` for WebSocket and Telnet ingress.
- Add or wire an independent deadman/heartbeat mirror such as `observability_deadman_heartbeat_timestamp_seconds{source}` whose authoritative paging path does not depend on Prometheus being healthy.
- Add canonical alert rules and non-production failure-injection proof for canary, blackbox, and deadman paths without paging production destinations.
- Retain operator-run evidence for first-live, post-restore, and other documented traffic-open events where prod-like observability smoke is required.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Out of Scope

- Replacing the normal live-traffic SLI/SLO model.
- Building a broad synthetic player generator or load-test platform.
- Paging production destinations from CI or ordinary pull-request validation.
- Adding high-cardinality canary identity labels to metrics.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Locked Direction

- Synthetic identities must be dedicated non-player identities and clearly marked as synthetic in authoritative account/session state.
- Canary metrics stay low-cardinality and bounded by `flow`, `path`, and `target`.
- The external deadman path must be capable of paging when Prometheus or in-cluster alert evaluation is unavailable.
- Non-production failure injection must prove the alert paths without sending production pages.
- Traffic-open evidence should reference retained canary/blackbox/deadman smoke results rather than relying only on a manual statement that monitoring exists.
- The first canonical canary flow is intentionally narrow: successful synthetic login followed by one representative gameplay command, currently `LOOK`.
- WebSocket and Telnet/TCP reachability remain separate blackbox probes even when the player-flow canary itself uses only one primary transport implementation at first.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Acceptance Shape

- Prod-like smoke can verify canonical player-flow canary metrics for login and one representative command path, with `LOOK` as the first required command proof.
- Prod-like smoke can verify entry-path blackbox probe metrics for WebSocket and Telnet ingress as distinct network/entry-path checks rather than inferring them from login success alone.
- Prod-like smoke can verify the independent deadman heartbeat mirror and configured staleness threshold.
- Alert rules exist for canary failure, canary latency, entry-path blackbox failure, and deadman staleness with bounded routing labels and runbook references.
- A non-production proof can force controlled canary, blackbox, and deadman failure modes without paging production destinations.
- Traffic-open evidence records the smoke/preflight reference, operator identity, timestamp, and retained results for canary, blackbox, and deadman checks.

##### source-02-14-5-task-list-player-experience-canary-and-deadman-smoke-vertical-slice-1-83: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51

#### Canonical Demo Seed State Hardening Vertical Slice - Canonical demo and smoke seed state (source lines 1-51)

##### Preserved Source Text: source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51

<!-- migration-source path="design/project-management/vertical-slices/02.14.6-task-list-canonical-demo-seed-state-hardening-vertical-slice.md" lines="1-51" sha256="cf40980773baebc4cc3400f3886f64f717f06fc229146cdb4e708b14d9007ac6" heading-offset="3" -->
#### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Canonical Demo Seed State Hardening Vertical Slice

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Goal and Status

Goal: make the prod-like smoke and hosted preview/demo seeders reassert one canonical demo state instead of seeding only when tables happen to be empty. Status: implemented at the current smoke/runtime boundary.

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Why This Slice Exists

The player-experience and hosted-preview audit work exposed that several seeders still assumed an empty database was the only important bootstrap case. That is not strong enough for persistent preview namespaces, restart-heavy local compose proof, or any environment where old rows can survive while still drifting away from the canonical smoke contract.

Without an explicit slice, this problem looks like a string of unrelated bootstrap bugs when it is really one shared contract issue: demo/runtime seeders must be able to repair canonical state, not just create it once.

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Scope

- Reassert canonical demo account/runtime rows for the smoke path instead of relying on `count() == 0`.
- Keep hosted preview/dev-demo bootstrap and local compose smoke aligned on the same demo credentials and runtime proof rows.
- Add focused repository/test proof for any persistence seams exposed while strengthening seed reassertion.
- Normalize the remaining in-scope runtime/demo seeders that still trust emptiness over canonical row identity.

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Out of Scope

- Turning every seeder into a broad fixture framework.
- Seeding richer authored gameplay content beyond the current canonical smoke/demo contract.
- Reworking unrelated non-smoke sample data that is not part of the player bootstrap/runtime proof path.

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Implementation Notes

The current pass closes the known canonical-state gaps that matter for smoke and hosted preview:

- hosted preview/dev-demo bootstrap now uses the canonical account bootstrap flow instead of the stale direct account-create path;
- preview/dev-demo example values now enable the same demo runtime seeding contract local compose relies on;
- Account Service now reasserts the canonical demo account and membership state rather than trusting any preexisting row;
- Game Design now reasserts the canonical published demo game/template/version/revision/release-bundle/asset-artifact proof rows, and repository handling was fixed where Postgres timestamp materialization exposed a real mismatch;
- Game Session now reasserts the canonical manifest/feature-flag/running-instance rows instead of treating “any existing row” as sufficient;
- Logging Admin now reasserts canonical smoke-facing log/report/moderation rows by stable business identity rather than raw table emptiness;
- World Management now reasserts the canonical authored starter topology by natural demo identity and then layers runtime topology creation on top of that substrate instead of gating authored topology on global `region` table emptiness;
- the later `02.14.6.1` follow-through also moved that World Management smoke/runtime target seeding off `GameplayCatalogProperties` and onto an explicit runtime-target seed model that honors the shared bootstrap env overrides;
- the canonical fresh-bootstrap smoke script now shells helper scripts explicitly so file-mode drift cannot silently break the repo-owned proof path.

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Acceptance Shape

- Persistent preview/dev-demo namespaces can recover the canonical demo login/runtime state on restart without manual row cleanup.
- Local fresh bootstrap proof can rebuild and pass from a clean teardown using only repo-owned canonical scripts.
- The current smoke/demo seeders in scope no longer depend on raw table emptiness where canonical business identity is available.
- Focused tests exist for newly exposed persistence or seeding seams.

##### source-02-14-6-task-list-canonical-demo-seed-state-hardening-vertical-slice-1-51: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71

#### 02.14.6.1 Task List: Smoke Runtime Target Seed Model Isolation Vertical Slice - Runtime-target smoke seeding (source lines 1-71)

##### Preserved Source Text: source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/02.14.6.1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice.md" lines="1-71" sha256="b55d49a8a36ee2a74621b73292932e7112a81eeac51415514173aa44140a380f" heading-offset="3" -->
#### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: 02.14.6.1 Task List: Smoke Runtime Target Seed Model Isolation Vertical Slice

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Goal and Status

Goal: keep the World Management smoke/demo runtime seeder on an explicit runtime-target seed model instead of reusing the shared gameplay catalog config shape, while preserving the same canonical local bootstrap targets. Status: complete at the current bounded boundary.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Why This Slice Exists

`02.14.6` had already hardened canonical demo/runtime seeding, but one smoke-only model drift still survived:

- `world-management-service` `TestDataSeeder` still read `GameplayCatalogProperties` in main code to decide which runtime targets needed demo topology materialization;
- that reused the old gameplay-catalog authority shape in a smoke seeder that only needed `{tenantId, gameInstanceId}` targets;
- it also meant World Management could not honor the bootstrap env overrides already exposed by Game Session because the catalog defaults were hardcoded inside the shared properties class.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Implementation Notes

- Added dedicated `SmokeDemoRuntimeSeedProperties` in World Management for explicit runtime-target seed entries.
- `TestDataSeeder` now iterates those target entries directly instead of walking `GameplayCatalogProperties.World` / `Realm`.
- World Management default config now declares the canonical demo and sandbox runtime targets explicitly and wires them through the same bootstrap env vars the rest of the stack already exposes.
- Focused seeder proof was updated to build the new target model directly.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Scope

- World Management smoke/demo runtime target seed wiring;
- focused `TestDataSeeder` proof for the new target model;
- slice documentation for the canonical smoke runtime target seed contract.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Out of Scope

- redesigning broader demo/content seed semantics;
- removing Game Session admission-pointer bootstrap seeding;
- live routing or connect-token behavior outside smoke/demo topology materialization.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Locked Direction

- smoke/demo seeders may stay explicit and bounded, but they should use the narrowest seed model that matches the actual bootstrap write contract;
- runtime-target seeders should honor the same bootstrap target env overrides already surfaced elsewhere in the stack;
- shared gameplay catalog config should not stay in main-code smoke seeders once only test/fallback shaping still needs it.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Planned Work

###### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: 1. Seed Model Isolation

- [x] Add dedicated smoke runtime-target seed properties for World Management.
- [x] Move `TestDataSeeder` onto the explicit target list.
- [x] Keep the canonical demo/sandbox runtime targets unchanged while honoring existing env overrides.

###### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: 2. Proof and Documentation

- [x] Update focused seeder proof.
- [x] Align the `02.14.6` family docs with the isolated runtime-target seed model.
- [x] Re-run World Management validation and fresh bootstrap smoke.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Acceptance Shape

- World Management main code no longer imports `GameplayCatalogProperties`;
- smoke runtime topology seeding still materializes the canonical demo/sandbox targets;
- World Management now respects the same `FIREMUD_BOOTSTRAP_*` target overrides as the other bootstrap-sensitive services.

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Validation

- `./gradlew :world-management-service:test --tests 'net.firedevops.firemud.worldmanagement.data.TestDataSeederTest'`
- `./gradlew :world-management-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`

##### source-02-14-6-1-task-list-smoke-runtime-target-seed-model-isolation-vertical-slice-1-71: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67

#### Repo-Wide Metrics Policy Convergence and Smoke Evidence Freshness Vertical Slice - Observability policy and smoke-evidence freshness (source lines 1-67)

##### Preserved Source Text: source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67

<!-- migration-source path="design/project-management/vertical-slices/02.14.7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice.md" lines="1-67" sha256="fa16d1cf2c03d0d57745e39d1d805607302d795154adf9a71de82be65574fd49" heading-offset="3" -->
#### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Repo-Wide Metrics Policy Convergence and Smoke Evidence Freshness Vertical Slice

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Goal and Status

Goal: finish the remaining repo-level observability-policy follow-through after `02.14.4` and `02.14.5` so the canonical metrics-cardinality rule, shipped Prometheus rules, observability docs, and player-experience smoke evidence contract all teach the same bounded-label and retained-evidence story. Status: implemented.

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Why This Slice Exists

The earlier observability hardening slices landed the most important behavior:

- gameplay/runtime metrics no longer drift through obvious raw `tenantId` and `script_patch_version` labels;
- a static guardrail exists for obvious forbidden metric-label patterns;
- the player-experience smoke runner now proves the real public bootstrap/connect-token/WebSocket/Telnet path and requires external-authority evidence to be retained for non-simulated runs.

What remains is smaller but still audit-worthy:

- the current metrics-cardinality guardrail only scans a narrow subset of canonical docs, so the broader repo can still teach forbidden raw `tenantId` / `regionId` / per-script label patterns without detection;
- the shipped Prometheus rules and observability catalogs still need to align fully with the “bounded `scope`, not raw runtime identifiers” contract;
- the external-authority smoke evidence contract is shape-validated today, but not freshness-validated against the smoke execution window.

This slice is the bounded cleanup pass that makes the repo-level observability contract coherent instead of only locally correct in a few files and scripts.

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Scope

- expand the metric-cardinality guardrail to cover the canonical observability docs, metrics catalogs, and shipped Prometheus rules that still teach or encode raw runtime-identifier labels;
- align those docs/rules/examples to the intended low-cardinality metrics contract;
- decide whether player-experience external-authority evidence needs freshness validation at the current architecture boundary, and either implement it or document the explicit retained-evidence limit truthfully.

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Out of Scope

- redesigning the overall monitoring stack;
- inventing high-cardinality Prometheus exceptions for tenant-, region-, script-, or plugin-level drilldown;
- rewriting unrelated dashboards or exploratory example artifacts outside the canonical observability path unless they are directly teaching the wrong bounded-label contract.

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Checklist

- [x] Expand `check-metrics-cardinality.py` to scan the canonical observability docs, metrics catalogs, and rules that can currently drift undetected.
- [x] Remove or normalize remaining raw `tenantId` / `regionId` / per-script/plugin metric-label teaching in canonical docs and rules.
- [x] Decide and document the intended freshness contract for retained external-authority smoke evidence.
- [x] Implement freshness validation if the current target-state contract requires it; otherwise tighten the docs so they explicitly describe retained-shape validation only.

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Implementation Notes

Implemented:

- the static metrics-cardinality guardrail now scans the Redis metrics catalog, the scripting quotas/operations contract, and the shipped `prometheus-rules-firemud.yaml` file in addition to the earlier narrower canonical doc set;
- the same guardrail now rejects forbidden raw labels not only inside `metric{...}` examples but also inside PromQL `sum by (...)` and `on (...)` grouping/join clauses, so recording-rule drift is caught instead of only prose drift;
- the canonical Redis metrics catalog now teaches bounded `scope` / `scope_type` labels instead of raw `tenantId` / `regionId` shapes for ordinary Prometheus series;
- the scripting quotas/operations doc now aligns its canonical metrics examples with the bounded `scope`, `script_category`, `plugin_family`, `plugin_version_family`, `component_class`, and `tier` vocabulary already taught by the authoritative observability contract;
- the shipped Prometheus rules now aggregate and join the tick/tail-loss recording rules on bounded `scope` rather than raw `tenantId` / `regionId`;
- the external monitoring contract now explicitly records the current retained-evidence freshness decision: the repository validates authoritative evidence shape and required green-state semantics only, while contemporaneous evidence selection remains an environment/operator responsibility because `evidenceRef` and `checkRef` are intentionally opaque external-monitor handles.

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Validation

- [x] `python3 dev-tools/observability/check-metrics-cardinality.py`
- [x] `bash dev-tools/tests/player-experience-smoke-runner-contract.sh`
- [x] `bash dev-tools/tests/player-experience-smoke-evidence-contract.sh`
- [x] `./gradlew linkCheck lintMarkdown`

##### source-02-14-7-task-list-repo-wide-metrics-policy-convergence-and-smoke-evidence-freshness-vertical-slice-1-67: Validation Target

At minimum, this slice should close with:

- `python3 dev-tools/observability/check-metrics-cardinality.py`
- `bash dev-tools/tests/player-experience-smoke-runner-contract.sh`
- `bash dev-tools/tests/player-experience-smoke-evidence-contract.sh`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73

#### Hosted Preview Manual Proof Vertical Slice - Hosted preview operations and proof (source lines 1-73)

##### Preserved Source Text: source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73

<!-- migration-source path="design/project-management/vertical-slices/02.15-task-list-hosted-preview-manual-proof-vertical-slice.md" lines="1-73" sha256="d706d4f759427ef39b42289da3f94bca6af4f301ed3733453c85a67ca7f25c73" heading-offset="3" -->
#### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Hosted Preview Manual Proof Vertical Slice

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Goal and Status

Turn pull-request preview from render-and-validate infrastructure into a real reviewer-usable hosted environment, with manual proof focused first on `LOGIN -> PLAY -> LOOK` over the TCP Proxy Service. Status: baseline live; real Helm deploy, preview TCP exposure, and hosted smoke are now implemented, while lifecycle/doc-first convergence remains follow-on work.

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Why This Slice Exists

FireMUD's preview infrastructure is now close enough that the expensive mistake would be drifting into browser-helper work before the hosted environment can even be proved manually. The first useful reviewer target is not a rich web UI. It is a real PR preview instance that a human can connect to and use from a terminal or Mudlet-style client.

This slice locks in two decisions:

- Preview usefulness comes from a real hosted stack and a manual proof path first, not from CI-only render validation.
- The first manual proof path should be TCP/Telnet through the TCP Proxy Service, not browser-first bootstrap work.

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Scope

- Make preview workflow deploy real Helm releases instead of stopping at dry-run validation.
- Ensure preview stack has the minimum seeded world/state needed for `LOGIN -> PLAY -> LOOK`.
- Expose a reviewer-usable TCP endpoint for the preview namespace in addition to the preview HTTPS hostname.
- Keep preview TCP exposure on a small explicit preview-only port range rather than treating the full Kubernetes NodePort range as part of the public contract.
- Add a hosted smoke or proof step that validates manual reviewer prerequisites for TCP/Telnet access.
- Keep preview transport exceptions explicit. If preview temporarily relaxes internal gRPC transport to plaintext while bootstrapping reviewer usability, that choice must be written down as a preview-only implementation note and paired with follow-up cleanup slices rather than becoming an accidental permanent pattern.
- Keep the first acceptance target narrow:
  - connect
  - `LOGIN`
  - `PLAY demo`
  - `LOOK`

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Out of Scope

- Rich browser UI
- Full first-party web bootstrap UX
- Inventory, movement, or broader gameplay completeness
- Long-term frontend service implementation

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Architecture Notes

- Hosted preview should remain a real prod-like stack, not a special gameplay architecture.
- Preview proof must not depend on embedding product-specific browser helper logic into Spring Cloud Gateway as the primary path.
- If a temporary browser helper exists during implementation, it is subordinate to the TCP-first preview proof goal and should not redefine the long-term frontend architecture.
- Reviewer-usable preview proof remains TCP/Telnet-first until the hosted environment is solid enough to stand on its own; browser convenience work must not become the gating path for proving preview usefulness.

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Acceptance Shape

- A same-repo PR can deploy a hosted preview namespace and running Helm release.
- A reviewer can connect to the preview over TCP/Telnet using a normal terminal client or Mudlet-style client.
- The reviewer can manually prove `LOGIN -> PLAY -> LOOK`.
- Preview docs clearly state that TCP-first manual proof is the first hosted milestone.

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Remaining Follow-Through

- Keep preview docs aligned with the live workflow now that preview is a real deploy path rather than a render-only milestone.
- Keep the current clean-redeploy lifecycle explicit until preview-state persistence is either implemented deliberately or rejected as a contract.
- Keep broader Kubernetes deployment-contract convergence tracked separately from this hosted-preview milestone.

##### source-02-15-task-list-hosted-preview-manual-proof-vertical-slice-1-73: Follow-On

Once hosted preview manual proof is stable, the next browser-facing step should be the dedicated first-party web application service rather than growing ad hoc product-specific web helper logic inside Spring Cloud Gateway.

Related follow-on slices:

- `02.15.1` preview internal gRPC transport alignment and implementation-note closeout
- `02.15.2` repo-wide Spring gRPC server TLS bundle migration
- `02.15.3` preview rollout diagnostics and fast-failure visibility
- `02.15.4` gRPC transport configuration sanity checks in local tooling and CI
- `02.15.9` Kubernetes deployment-contract convergence for player-facing overlays and hosted-environment parity
<!-- /migration-source -->

### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46

#### Preview Internal gRPC Transport Alignment Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-46)

##### Preserved Source Text: source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46

<!-- migration-source path="design/project-management/vertical-slices/02.15.1-task-list-preview-internal-grpc-transport-alignment-vertical-slice.md" lines="1-46" sha256="c9cfa0265f58d04e28abe0adf698c8d5c7dda1b61399383c000b55c6439dee1d" heading-offset="3" -->
#### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Preview Internal gRPC Transport Alignment Vertical Slice

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Goal and Status

Make the hosted preview environment's internal gRPC transport stance explicit and aligned with the canonical non-local mTLS contract. Status: completed.

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Why This Slice Exists

Hosted preview had drifted into an ambiguous state where docs described a plaintext exception while the checked-in chart values and service configuration already expressed the SSL-bundle mTLS contract. This slice records the resolution of that ambiguity.

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Scope

- State that hosted preview uses the same bundle-based internal gRPC mTLS contract as other Kubernetes-backed environments.
- Document the current preview TCP bootstrap contract explicitly:
  - preview smoke and manual proof require a bootstrap gameplay session before `LOGIN`
  - preview currently provides that session through preview-only default bootstrap metadata and workflow-created seed state
  - that contract is intentionally preview-only and must not be treated as the long-term TCP admission model
- Ensure preview docs, deployment-environment docs, and chart values all say the same thing.

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Out of Scope

- Designing a different preview topology
- Changing the long-term mTLS target state

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Architecture Notes

- Preview must not silently create a second long-term internal gRPC transport model.
- The current preview TCP bootstrap path remains temporary operational glue. It exists to make the hosted preview reviewer-usable while the game-admission path remains under active hardening.

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Acceptance Shape

- Design docs explicitly state that hosted preview uses the canonical internal gRPC mTLS contract.
- The preview TCP bootstrap contract is described in the preview README and in this slice so the fixed bootstrap metadata path is not just an implementation detail in workflow code.
- The preview README, deployment-environment docs, and chart values all describe the same transport contract.

##### source-02-15-1-task-list-preview-internal-grpc-transport-alignment-vertical-slice-1-46: Follow-On

- `02.15.3` preview rollout diagnostics and visibility
- `02.15.4` gRPC transport configuration sanity checks
- `02.15.5` preview TCP admission cleanup
<!-- /migration-source -->

### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70

#### Spring gRPC Server TLS Bundle Migration Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-70)

##### Preserved Source Text: source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70

<!-- migration-source path="design/project-management/vertical-slices/02.15.2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice.md" lines="1-70" sha256="d1aa1a68d3c3aa80802185f8571c2b160508774fc49a99f49dfeb959db1c948a" heading-offset="3" -->
#### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Spring gRPC Server TLS Bundle Migration Vertical Slice

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Goal and Status

Replace the repository's older gRPC server TLS property pattern with the Spring gRPC `1.0.x` SSL-bundle model so internal gRPC mTLS is configured correctly and consistently across services. Status: complete.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Why This Slice Exists

FireMUD now uses Spring gRPC `1.0.x`, but several services still express server TLS as though older top-level `grpc.server.*` file-path properties remain authoritative. In practice this leaves gRPC servers plaintext while clients attempt TLS, which is a bad failure mode:

- configuration looks secure in YAML
- runtime behavior is actually plaintext
- preview and readiness failures become slow and opaque
- the same incorrect pattern can spread across more services if left in place

This needs a direct, repo-wide canonical correction while the service graph is still manageable.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Implementation Notes

- The runtime service configs should converge on Spring Boot SSL bundles under `spring.ssl.bundle.pem.firemud-grpc`, then bind the server side with `spring.grpc.server.ssl.enabled=true`, `spring.grpc.server.ssl.bundle=firemud-grpc`, and `spring.grpc.server.ssl.client-auth=REQUIRE` where client certificates are mandatory.
- The preview transport-alignment slice `02.15.1` now records the settled hosted-preview mTLS contract; this slice exists to keep the bundle-based server contract explicit and hard to regress everywhere it is intended to run.
- The migration is now complete in the repo: the canonical service configs use Spring Boot SSL bundles plus `spring.grpc.server.ssl.*`, and the legacy top-level `grpc.server.*` server-TLS pattern is no longer present in service runtime config.
- The static/CI transport sanity check from `02.15.4` is now live as the regression guard, so this slice no longer depends on a temporary allowlist.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Scope

- Standardize gRPC server TLS configuration on:
  - `spring.grpc.server.ssl.enabled`
  - `spring.grpc.server.ssl.bundle`
  - `spring.grpc.server.ssl.client-auth`
- Standardize service-local SSL bundle material using `spring.ssl.bundle.*` PEM configuration.
- Remove or replace older server-side TLS property shapes that Spring gRPC `1.0.x` does not honor.
- Update shared environment/secret docs so server and client TLS configuration are described accurately.
- Prove the migrated configuration in:
  - local stack validation,
  - smoke where appropriate,
  - hosted preview after preview/plaintext is removed.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Out of Scope

- Replacing the shared outbound gRPC client TLS model unless required
- Reworking Gateway WebSocket mTLS design
- Broad secret-management redesign

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Architecture Notes

- This is not a compatibility migration. FireMUD is still in initial development; the correct move is one canonical server-TLS model repo-wide.
- Bundle-based TLS should become the only documented non-local server-gRPC pattern.
- Preview plaintext remains allowed only until this slice is complete and preview mTLS is re-proved.
- The migration is now protected by a direct transport sanity check with no remaining allowlist, so any reintroduction of the legacy server-TLS property shape fails fast in local tooling and CI.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Acceptance Shape

- Services no longer rely on ignored or misleading gRPC server TLS properties.
- One canonical SSL-bundle pattern is documented and used across services.
- Hosted preview and other non-local Kubernetes-backed environments can be brought back to internal gRPC mTLS using the same runtime contract.
- Obsolete server-TLS documentation and config examples are removed.
- The transport sanity check passes in direct enforcement mode with no temporary allowlist.
- The design docs point at the preview transport-alignment slice rather than implying that preview should silently share a second transport contract.

##### source-02-15-2-task-list-spring-grpc-server-tls-bundle-migration-vertical-slice-1-70: Verification Notes

- `bash dev-tools/validation/check-grpc-transport-config.sh --enforce` now passes, proving no service runtime config files still use the legacy server-TLS property shape.
- `02.15.4` is live as the guardrail that keeps this slice closed.
<!-- /migration-source -->

### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70

#### Preview Rollout Diagnostics and Fast-Failure Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-70)

##### Preserved Source Text: source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70

<!-- migration-source path="design/project-management/vertical-slices/02.15.3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice.md" lines="1-70" sha256="8759df0c54b922c15ae5762427ab5c601ce1fd2746fbe23d39e04c2b7ed9dc57" heading-offset="3" -->
#### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Preview Rollout Diagnostics and Fast-Failure Vertical Slice

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Goal and Status

Make hosted preview fail faster and explain itself better so preview debugging does not depend on waiting blind through long Helm timeouts. Status: complete at the current bounded boundary.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Why This Slice Exists

Preview iteration has repeatedly shown the same waste pattern:

- Helm waits for a long time
- only one or two pods remain unready
- the real root cause lives in a subtle config or transport mismatch
- the first useful signal arrives only after timeout or manual live inspection

That is too expensive for a pre-`06` proof path that should be operationally boring.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Scope

- Expand preview workflow diagnostics to surface the effective runtime picture earlier:
  - key preview ConfigMap values
  - service and target ports
  - cert/secret summaries where safe
  - readiness dependency names and transitions
- Tighten failure-phase visibility so stuck rollouts expose the first blocked dependency rather than only Helm timeout.
- Prefer diagnostics that are cheap, deterministic, and safe to capture from the preview runner.
- Document the expected debugging contract for preview operators.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Out of Scope

- Replacing Helm
- Building a general observability platform for preview
- Broad RBAC redesign beyond what preview debugging truly needs

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Architecture Notes

- Hosted preview is an operator workflow as much as an application workflow.
- Preview diagnostics should reveal configuration truth, not just pod health.
- Fast-failure visibility remains important even after transport alignment, because preview still carries scoped operational distinctions such as clean-redeploy lifecycle and preview-only bootstrap state.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Implementation Notes

- 2026-06-29: Hosted rollout failure diagnostics now converge on one reusable helper under `dev-tools/hosted/shared/` instead of preview-only or deploy-only inline YAML blocks. Preview and dev-demo both invoke the same script after cluster access succeeds, and the helper now prints namespace target metadata, blocked workload/pod readiness reasons, service and target-port detail, safe selected ConfigMap values, secret and TLS summaries, recent events, unavailable workload describes, and current plus previous logs for problematic pod containers.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Completion Evidence

- Shared helper and lane wiring:
  - `dev-tools/hosted/shared/show-rollout-diagnostics.sh`
  - `dev-tools/hosted/preview/show-preview-rollout-diagnostics.sh`
  - `.github/workflows/preview.yml`
  - `.github/workflows/dev-demo.yml`
- Operator-facing contract docs:
  - `dev-tools/hosted/README.md`
  - `dev-tools/hosted/shared/README.md`

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Acceptance Shape

- A stuck preview run shows enough context to identify the first blocked dependency without ad hoc host spelunking in common cases.
- Preview docs describe where operators should look first for rollout truth.
- Repeated preview debugging does not depend primarily on Helm timeouts.

##### source-02-15-3-task-list-preview-rollout-diagnostics-and-fast-failure-vertical-slice-1-70: Follow-On

- Further RBAC/runner visibility improvements if diagnostics still prove too weak.
<!-- /migration-source -->

### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54

#### gRPC Transport Configuration Sanity Checks Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-54)

##### Preserved Source Text: source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54

<!-- migration-source path="design/project-management/vertical-slices/02.15.4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice.md" lines="1-54" sha256="9266f284cc4848a5230aa782dc92d5463324f7e60141050c76c9670322ee28d7" heading-offset="3" -->
#### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: gRPC Transport Configuration Sanity Checks Vertical Slice

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Goal and Status

Add lightweight local and CI checks that catch obviously wrong gRPC transport-configuration patterns before smoke or preview startup does. Status: implemented.

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Why This Slice Exists

Several recent failures were not novel runtime bugs. They were configuration-shape mistakes that should have been rejected much earlier:

- legacy/ignored gRPC server TLS property usage
- transport mismatches where clients assume TLS but servers are plaintext
- preview/runtime config that appears secure in YAML but is ineffective at runtime

FireMUD should treat those as sanity-check problems, not just integration-test discoveries.

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Scope

- Add a fast static check that flags known-wrong or legacy gRPC server TLS property usage for the current Spring gRPC stack, including the old `grpc.server.security.*` shape and any other property set that Spring gRPC `1.0.x` does not honor.
- Run that check as a direct guard now that the legacy migration is complete, with no temporary allowlist remaining.
- Add a validation path that asserts preview/runtime transport choices are explicit rather than accidental.
- Wire these checks into local tooling and CI so they fail early.
- Update documentation so contributors know which transport property model is canonical and when the temporary allowlist is expected to disappear.

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Out of Scope

- Full semantic proof of all runtime TLS handshakes
- Replacing smoke or preview integration tests
- General-purpose static analysis beyond transport-shape hygiene

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Architecture Notes

- These checks are a guardrail, not the proof itself.
- Smoke and preview still matter, but they should not be the first place obvious configuration drift is discovered.
- The sanity checks should be narrow and high-signal so contributors trust them.
- The earlier allowlist-based migration aid is complete. The guard should remain direct and fail on any reintroduction of the legacy property shape.

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Acceptance Shape

- CI fails quickly when a service reintroduces the obsolete server-TLS property pattern.
- Local contributors can run `./gradlew checkGrpcTransportConfig` before pushing, or `./dev-tools/validation/check-grpc-transport-config.sh` when they want the raw script entrypoint.
- The guard remains simple and direct because no transitional allowlist remains.
- The canonical transport config model is referenced from the relevant design docs and slice docs.
- Preview plaintext remains explicitly documented as a temporary exception rather than being inferred from the guardrail implementation.

##### source-02-15-4-task-list-grpc-transport-configuration-sanity-checks-vertical-slice-1-54: Follow-On

- Expand checks if new repeated transport-shape failures appear in `ai-observations.md`
<!-- /migration-source -->

### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60

#### Preview TCP Admission Cleanup Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-60)

##### Preserved Source Text: source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60

<!-- migration-source path="design/project-management/vertical-slices/02.15.5-task-list-preview-tcp-admission-cleanup-vertical-slice.md" lines="1-60" sha256="f0e0a4fdd27911d6ee9601da1fc2f67a7df253a56afcfd304469f9819012e555" heading-offset="3" -->
#### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Preview TCP Admission Cleanup Vertical Slice

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Goal and Status

Retire the preview-only TCP bootstrap glue once the hosted preview admission story is clean enough to stand on its own, so preview TCP either uses a deliberate first-party admission path or a clearly defined minimal bootstrap contract rather than workflow-specific session hacks. Status: planned.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Checklist

- [ ] Define target-state behavior and scope.
- [ ] Implement the slice end-to-end.
- [ ] Verify and close any follow-ups.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Why This Slice Exists

Hosted preview currently uses preview-only operational glue to make `LOGIN -> PLAY -> LOOK` workable over TCP:

- the preview workflow creates the smoke account in tenant `1`
- preview tcp-proxy receives preview-only default bootstrap metadata
- preview smoke expects a bootstrap gameplay session to exist before `LOGIN`

That is acceptable as a temporary reviewer-usable contract, but it is not the long-term TCP admission model. The cleanup work needs its own slice so the repo can converge on one explicit target state instead of leaving the current workaround to drift indefinitely.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Scope

- Define the target-state TCP admission contract for hosted preview in design terms.
- Decide whether preview TCP should keep a fixed bootstrap session path or move to a cleaner first-party admission path.
- Remove preview-only bootstrap assumptions from the active preview workflow once the replacement contract is ready.
- Update preview documentation and the related architecture docs so the new admission model is described canonically rather than as workflow lore.
- Keep the current preview-only bootstrap path isolated until the replacement is proven.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Out of Scope

- Reworking the broader first-party web or gateway architecture beyond what TCP admission needs.
- Changing the current preview smoke contract before the replacement admission path exists.
- Broad gameplay-admission UX redesign outside the preview TCP path.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Architecture Notes

- Preview TCP admission should converge on one clear contract, not a hidden mix of:
  - workflow-created seed state,
  - tcp-proxy default metadata,
  - and session-scoped preview exceptions.
- If preview keeps a bootstrap session path, it should remain explicitly preview-only and narrowly documented.
- If preview moves to a first-party admission path, that path should be designed deliberately rather than inferred from existing login/session behavior.
- The long-term design should be understandable without reading GitHub Actions history.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Acceptance Shape

- The preview TCP admission path is documented as either:
  - a retained preview-only bootstrap contract with clear boundaries, or
  - a replacement first-party admission path with no hidden workflow glue.
- Preview docs no longer rely on commit history or chat context to explain how TCP admission works.
- The chosen target state is reflected in the preview README and the preview transport/admission slices.

##### source-02-15-5-task-list-preview-tcp-admission-cleanup-vertical-slice-1-60: Follow-On

- `02.15.1` preview internal gRPC transport alignment
- `02.15.2` Spring gRPC server TLS bundle migration
- `02.15.3` preview rollout diagnostics and fast failure
- `02.15.4` gRPC transport configuration sanity checks
<!-- /migration-source -->

### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72

#### Fixed Develop Dev-Demo Environment Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-72)

##### Preserved Source Text: source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72

<!-- migration-source path="design/project-management/vertical-slices/02.15.6-task-list-fixed-develop-dev-demo-environment-vertical-slice.md" lines="1-72" sha256="e4181c6a8130f6bf06ac8f75984c5ca991212072ca71d63846e62973c4808810" heading-offset="3" -->
#### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Fixed Develop Dev-Demo Environment Vertical Slice

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Goal and Status

Keep one fixed hosted `develop` environment on the Hetzner preview cluster so the current `develop` branch always has a stable, reviewer-usable HTTPS and TCP endpoint separate from per-PR previews. Status: implemented.

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close any follow-ups.

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Why This Slice Exists

Per-PR preview is useful for branch-specific validation, but it is not the same thing as having one stable shared environment for the latest `develop` head. A fixed `develop` environment makes it easier to:

- keep one always-available manual smoke target;
- share a stable hostname and TCP endpoint;
- validate current shared branch behavior without needing an open PR;
- avoid treating PR preview as the only hosted environment below staging.

This slice deliberately uses the same Hetzner-backed preview cluster while keeping the `develop` environment operationally separate from PR preview namespaces.

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Scope

- Add a dedicated GitHub Actions workflow that deploys the current `develop` head to a fixed namespace and release.
- Add a light scheduled reconciler that redeploys when the fixed namespace is missing or stale relative to `develop`.
- Keep the deployment on the same preview cluster and runner stack already used for hosted PR previews.
- Use a fixed hostname and fixed TCP NodePort rather than the per-PR namespace and allocated-port model.
- Reuse the same Helm chart and hosted smoke proof shape (`LOGIN -> PLAY -> LOOK`) as the current preview stack.
- Rebuild the fixed namespace from clean state on each `develop` deploy while keeping the address stable.
- Keep the fixed `develop` environment outside preview janitor/capacity logic and outside PR-comment reporting.
- Document the environment as the canonical `dev-demo-cluster` hosted environment rather than as “another preview.”

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Out of Scope

- Replacing staging as the prod-like promotion candidate.
- General platform environment redesign.
- Sharing namespace lifecycle with PR preview namespaces.
- Reworking the Helm chart’s preview-oriented internal value names as part of this slice.

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Architecture Notes

- `dev-demo-cluster` is a fixed shared environment class, not a PR preview.
- It is allowed to reuse the same single-node Hetzner cluster and runner label as hosted preview, but it must keep separate namespace identity, stable addressing, and independent lifecycle.
- The canonical fixed deployment target is:
  - namespace `dev`
  - release `dev`
  - hostname `dev.preview.firedevops.net`
  - TCP port `32016`
- The fixed `develop` environment is informative and useful for continuous manual proof, but it is not a promotion source, rollback-evidence source, or DR-attestation source.
- Stable address does not imply persistent branch-local state. The current deploy path resets the namespace before redeploy so the environment stays reproducible and smoke/bootstrap paths remain deterministic.
- PR preview capacity and stale-namespace janitor logic should continue to operate only on `pr-*` preview namespaces and must not evict or count the fixed `dev` environment.

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Acceptance Shape

- Pushes to `develop` can deploy a full-stack hosted environment into namespace `dev`.
- A scheduled reconciler can recover the fixed environment when the namespace is missing or no longer aligned to the current `develop` head.
- Reviewers/operators have one stable web and TCP address for the current `develop` head.
- The workflow proves `LOGIN -> PLAY -> LOOK` against the fixed environment after deploy.
- Docs describe the fixed `develop` environment as `dev-demo-cluster`, separate from PR preview and staging.

##### source-02-15-6-task-list-fixed-develop-dev-demo-environment-vertical-slice-1-72: Follow-On

- If the dev-demo environment becomes heavily used, resource reservation and explicit cluster-capacity policy should be revisited rather than inferred from PR preview assumptions.
- If the shared Helm chart’s `preview`-named value contract becomes a recurring source of confusion, split that into a more generic hosted-environment values seam later instead of doing ad hoc renaming here.

Related follow-on slices:

- `02.15` hosted preview manual proof
- `02.15.3` preview rollout diagnostics and fast-failure visibility
- `02.17` local reset and bootstrap proof tooling
<!-- /migration-source -->

### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83

#### `02.15.7` Gateway Edge Allowlist and Management Contract Convergence - Hosted preview, transport, deployment, or preflight operations (source lines 1-83)

##### Preserved Source Text: source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md" lines="1-83" sha256="5f7f9b056234905df5dc4c0af5bfb4eb2e192b87c772ae2f1b0cae50709170c1" heading-offset="3" -->
#### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: `02.15.7` Gateway Edge Allowlist and Management Contract Convergence

Goal: converge the live gateway route surface and gateway-management contracts on the documented edge model so public ingress exposes only intended external families and gateway management RPCs follow the repo’s shared gRPC contract rules. Status: implemented for the current explicit public route inventory and owner-side enforcement boundary, with future-route follow-through intentionally tracked in follow-up slices.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Implementation Notes

- Public gateway route config now keeps only the curated external edge families in canonical `routes.yml`; internal-only service families are no longer exposed on the public edge by default.
- Gateway-management proto/docs now use the canonical `gateway.v1` package naming and examples instead of older stale package references.
- Focused route validation now proves the allowed public route ids instead of relying on visual inspection of YAML drift.
- The `/api/session/**` family now resolves to an HTTP upstream in route configuration, matching the documented split between `/ws/game/**` gameplay WebSockets and `/api/session/**` HTTP control/admin routes, and the current public session HTTP inventory is reduced to `GET /api/session/ping` rather than a blanket `/sessions/**` forwarder.
- Gateway now blocks `/api/{public-family}/internal/**` requests at the edge so documented internal-only service-local subtrees do not leak through the coarse public route-family matcher.
- Audit follow-through still belongs here even after the coarse allowlist cleanup:
  - the canonical external namespace is now documented consistently as `/api/{service}/**` rather than older `/admin/...` examples in high-level architecture docs;
  - runtime convergence for the explicit edge inventory is now implemented for `/api/session/**`, `/api/admin/**`, and `/assets/**`;
  - gateway config now enforces deny-by-default behavior against undocumented paths by converging coarse `/api/{public-family}/**` families to explicit route entries.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Why This Slice Exists

The current gateway still has two live seams that drift from the architecture:

- the public route allowlist is broader than the documented external edge, still exposing internal-only service families;
- the gateway-management proto/docs drift from the shared gRPC contract conventions and from each other.

This is not just cleanup wording. It affects what the public edge exposes and how operator/control-plane integrations consume gateway management.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Scope

- public edge route allowlist convergence
- internal-only versus public-facing route families
- management-plane proto/docs contract alignment
- gateway-owned management response shapes and package naming consistency

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Out of Scope

- full dynamic-route persistence/convergence work
- broader first-party web-product routing redesign
- gameplay admission semantics beyond which edge families should be public

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Locked Direction

- the gateway should be a curated public edge, not a permissive service fan-out.
- internal-only domain families should not remain publicly routable by default.
- management proto/docs should match the repo’s shared gRPC application-error conventions.
- package names and examples should describe one canonical contract.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Acceptance Shape

- `routes.yml` and gateway auth filters match the documented external allowlist.
- gateway management proto/docs use one canonical package/contract story.
- `PingResponse` and related responses align with the repo’s shared in-band app-error conventions where applicable.
- docs and runtime route shape stop disagreeing about what is public and what is internal-only.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Checklist

- [x] Reconcile public edge allowlist docs with the live gateway route config.
- [x] Remove or internalize internal-only route families from the public edge surface.
- [x] Align gateway-management proto package names, examples, and response shapes with repo conventions.
- [x] Add focused validation proving public routes and management contracts match the documented edge model.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Deferred Follow-ups

- [x] Converge live Game Session and Gateway enforcement so the documented `/api/session/**` read/internal-only/operator-ingress split is reflected in handler enforcement as well as the now-explicit route inventory rather than only in family-level routing plus owner-side checks.
- [x] Complete the Logging & Admin operator-ingress follow-through so documented `/api/admin/**` runtime-control routes exist only when backed by real owner-side forwarding contracts; the current published admin inventory stays behind Logging & Admin owner-side privileged HTTP auth, and the bounded route-inventory plus session/admin owner-side enforcement follow-through in [`02.15.7.1`](../vertical-slices/02.15.7.1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice.md) is complete at the current boundary.
- [x] Keep the now-explicit public route inventory and the owning-service public/internal contracts converged as later public routes are added so fixtures and docs do not drift back toward coarse family forwarding.

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Completion Evidence

- Gateway allowlist and route-shape convergence implemented by canonical route configuration and tests:
  - [CanonicalGatewayRoutesConfiguration.java](../../../services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/config/CanonicalGatewayRoutesConfiguration.java)
  - [PublicInternalRouteBlockFilter.java](../../../services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/PublicInternalRouteBlockFilter.java)
  - [GatewayManagementGrpcService.java](../../../services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/service/impl/GatewayManagementGrpcService.java)
- [GatewayRoutesConfigurationProdTest.java](../../../services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationProdTest.java)
- Contract and runtime validation:
  - [GatewayRoutesConfigurationTest.java](../../../services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationTest.java)
  - [GatewayRoutesConfigurationTestProfileTest.java](../../../services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationTestProfileTest.java)
  - [GatewayManagementGrpcServiceAuthTest.java](../../../services/spring-cloud-gateway/src/test/java/unit/net/firedevops/firemud/springcloudgateway/service/impl/GatewayManagementGrpcServiceAuthTest.java)

##### source-02-15-7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice-1-83: Validation

- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :spring-cloud-gateway:check :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`
<!-- /migration-source -->

### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98

#### 02.15.7.1 Task List: Explicit Session and Admin Edge Route Inventory Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-98)

##### Preserved Source Text: source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98

<!-- migration-source path="design/project-management/vertical-slices/02.15.7.1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice.md" lines="1-98" sha256="5c4a3da990415dc11779c49562ce52fed1cc62d111a806985850a6ad1a471a07" heading-offset="3" -->
#### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: 02.15.7.1 Task List: Explicit Session and Admin Edge Route Inventory Vertical Slice

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Goal and Status

Goal: replace the remaining coarse external-family forwarding for `/api/session/**` and `/api/admin/**` with one explicit route inventory and matching owner-side enforcement so the gateway edge is deny-by-default beneath the documented public and privileged external contracts. Status: complete at the current bounded boundary.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Why This Slice Exists

`02.15.7` already removed broad internal-only families from the public edge and added the first `/internal/**` deny guard. One bounded gateway gap remains:

- `/api/session/**` and `/api/admin/**` had still depended too much on downstream owner-side checks even after the gateway route catalog became explicit;
- the documented edge contract is more specific than “everything under this family except a few known bad paths”, with only `GET /api/session/ping` public and the remaining session/admin routes staying external-but-privileged;
- lightweight fixtures can under-cover the real public contract unless the route inventory itself becomes explicit.

This slice keeps the work narrow: it is about explicit external route inventory and enforcement for the current session/admin/public-asset families, plus keeping the rest of the current public or privileged-external route catalog explicit so fixtures and docs teach one canonical edge contract, not a broader gateway redesign.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Scope

- gateway route inventory and deny-by-default behavior for `/api/session/**`, `/api/admin/**`, `/assets/**`, and the rest of the current documented public or privileged-external edge catalog;
- matching owner-side enforcement where the gateway contract depends on a documented internal-only versus public split;
- focused validation and doc refresh for the touched edge contract.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Out of Scope

- broader first-party web routing redesign;
- dynamic route persistence or general API gateway product work;
- undocumented future operator routes that do not yet have an owner-side contract.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Locked Direction

- the public edge is an explicit allowlist, not a coarse service-family fan-out with ad hoc exclusions;
- `/api/session/**` and `/api/admin/**` should expose only the documented external inventory, with only `GET /api/session/ping` public and the remaining session/admin routes staying privileged, and deny the rest by default;
- gateway route config, owner-side enforcement, docs, and fixtures should teach the same public-versus-privileged external contract.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Planned Work

###### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: 1. Explicit Route Inventory Audit

- [x] Enumerate the currently documented external session/admin/assets routes and the still-coarse live forwarding paths.
- [x] Identify any route that is currently public only because of family-level forwarding rather than an explicit contract.
- [x] Keep the batch bounded to the current external inventory, not future APIs.

###### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: 2. Edge and Owner-Side Convergence

- [x] Implement the explicit external route inventory for the touched families in Gateway, keeping the rest of the current public route catalog explicit at the same time.
- [x] Tighten owner-side enforcement where the documented public/internal split still depends on downstream checks.
- [x] Keep undocumented service-local paths denied by default at the edge instead of relying on ad hoc subpath guards.

###### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: 3. Fixtures, Proof, and Docs

- [x] Update route fixtures/tests so they prove the explicit inventory instead of only the coarse public families.
- [x] Refresh the touched docs to describe one canonical external route inventory.
- [x] Re-run focused gateway and owner-side proof, then refresh broad service and prod-like smoke evidence.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Completion Evidence

- Route inventory and route-level predicate/filter assertions now live in `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/config/CanonicalGatewayRoutesConfiguration.java` and are covered by focused route-ID and path assertions in:
  - `services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationTest.java`
  - `services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationProdTest.java`
  - `services/spring-cloud-gateway/src/test/java/net/firedevops/firemud/springcloudgateway/config/GatewayRoutesConfigurationTestProfileTest.java`
- Game Session owner-side session-control enforcement now lives in:
  - `services/game-session-service/src/main/resources/application.yml`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/controller/GameInstanceController.java`
  - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/controller/SessionRoleController.java`
  - `services/common-test-support/src/testFixtures/java/net/firedevops/firemud/test/HttpTestSupport.java`
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/GameSessionApplicationIntegrationTest.java`
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/controller/GameInstanceControllerTest.java`
  - `services/game-session-service/src/test/java/integration/net/firedevops/firemud/gamesession/controller/SessionRoleControllerTest.java`
- The canonical external route docs now match that bounded contract in:
  - `design/architecture/microservices/game-session-service/api-contracts.md`
  - `design/architecture/microservices/spring-cloud-gateway/client-behavior.md`

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Acceptance Shape

- `/api/session/**`, `/api/admin/**`, `/assets/**`, and the rest of the current public route catalog expose only the documented explicit external inventory, with the current session HTTP public surface reduced to `GET /api/session/ping`;
- undocumented or internal-only paths under those families are denied by default at the gateway edge;
- route config, owner-side enforcement, fixtures, and docs all describe the same public contract.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Spark Delegation Notes

- Keep the batch strictly on explicit external route inventory for the current session/admin/assets families.
- Audit the route matrix first, then repair Gateway and owner-side enforcement in one pass.
- Return the exact public routes inventory, exact changed files, and exact validation commands run.

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Suggested Starting Surfaces

- `services/spring-cloud-gateway`
- `services/game-session-service`
- `services/logging-admin-service`
- `design/project-management/vertical-slices/02.15.7-task-list-gateway-edge-allowlist-and-management-contract-convergence-vertical-slice.md`

##### source-02-15-7-1-task-list-explicit-session-and-admin-edge-route-inventory-vertical-slice-1-98: Validation

- `./gradlew spotlessApply`
- `./gradlew :game-session-service:integrationTest --tests 'net.firedevops.firemud.gamesession.controller.GameInstanceControllerTest' --tests 'net.firedevops.firemud.gamesession.controller.SessionRoleControllerTest' --tests 'net.firedevops.firemud.gamesession.GameSessionApplicationIntegrationTest'`
- `dev-tools/validation/run-locked-gradle.sh :spring-cloud-gateway:check :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh`
<!-- /migration-source -->

### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71

#### Environment Preflight and Secret-Binding Convergence Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-71)

##### Preserved Source Text: source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/02.15.8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice.md" lines="1-71" sha256="487b2896c06ba8a371dc2bc80048d1c924ee651f52dc293719d6fa4154f34a96" heading-offset="3" -->
#### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Environment Preflight and Secret-Binding Convergence Vertical Slice

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Goal and Status

Make the player-facing environment bootstrap and traffic-open checks executable by converging expected-binding manifests, preflight output, JWT/JWKS runtime wiring, and backup traffic-open evidence on the target contracts already documented in the architecture. Status: partially implemented.

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Implementation Notes

The first implementation pass is now live:

- `common-security` can initialize JWT signing from `firemud.auth.jwt-secret-path` without requiring an inline `firemud.auth.jwt-secret`, so file-mounted JWT signing material is a real canonical non-test startup path rather than only a documented target.
- Account Service can serve `/.well-known/jwks.json` from `firemud.auth.jwks-path` when a mounted JWKS file is configured, while preserving the classpath fallback for local/dev.
- The canonical Kubernetes application contract now expects environment-owned `jwt-signing-keys` and `jwt-jwks` resources, mounts JWT signing material into services, mounts JWKS into Account Service, and removes inline JWT secret material from the shared app Secret.
- `dev-tools/deploy/preflight.py` now consumes `design/operations/environments/<environment>/expected-bindings.yaml`, emits `expectedBindingsRef`, and emits the first implemented results for `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, and `PREFLIGHT-SERVICES-001`.
- The player-facing expected-binding manifests now include the required storage binding identity and concrete certificate binding fields needed by the first preflight schema validation.
- The preview/dev-demo Helm stack now renders JWT signing and JWKS resources separately from the shared app Secret, mounts them through the file-path contract, and the preview value renderer generates per-render signing/JWKS material so PR preview namespaces no longer inherit the static inline JWT secret by default.
- Preflight now accepts `FIREMUD_TRAFFIC_OPEN_EVENT=first-live|reopen` plus optional `FIREMUD_TRAFFIC_OPEN_EVIDENCE` and emits `PREFLIGHT-BACKUP-002` / `PREFLIGHT-BACKUP-003` for production and hobby traffic-open events instead of omitting those policy IDs.
- `dev-tools/tests/preflight-contract.sh` now proves the preflight report policy-ID set, `expectedBindingsRef`, routine hobby report shape, and hobby traffic-open `PREFLIGHT-BACKUP-003` success path against a synthetic rendered manifest.
- The JWT/JWKS preflight proof now parses rendered Kubernetes resources rather than relying on text matches, so it verifies the JWT signing path is backed by a mounted `jwt-signing-keys` Secret on primary workloads and the Account Service JWKS path is backed by the mounted `jwt-jwks` Secret.
- Production promotion validation now also proves the referenced staging preflight report exists, targets the staging expected-bindings manifest, and contains no failing required checks; hobby traffic-open validation now treats `preflightReportPath` the same way instead of accepting a decorative string.
- Preflight expected-binding validation now also treats the environment manifest as the owner of exact rendered binding identity, not only schema presence: it derives the expected bootstrap Secret names and registry pull-secret name from `expected-bindings.yaml`, fails when rendered workloads do not reference those exact bindings, and the staging/production Kustomize overlays now bind the environment-specific `ghcr-pull-*` image-pull Secret on the shared `firemud-app` ServiceAccount so the rendered contract actually matches the manifest.
- `dev-tools/tests/preflight-contract.sh` now also runs static preflight end to end for the real staging and production overlays instead of only a synthetic hobby render, so binding-identity drift in overlay/Kustomize wiring fails locally and in CI before any operator promotion flow depends on it.
- `dev-tools/deploy/write-traffic-open-evidence.py` now writes canonical production and hobby traffic-open evidence records instead of leaving those JSON records to ad hoc manual authoring, and the production/hobby preflight gates now require `schemaVersion`, operator identity, non-empty `evidenceRefs`, and a canonical referenced preflight report for both environments before the traffic-open backup checks pass.
- Preflight external-binding validation now also proves cross-environment isolation from the expected-binding manifests themselves instead of relying on environment-token heuristics: backup/object-store, asset-store, outbound SMTP/webhook targets, and operator credential bindings must be unique across player-facing manifests unless every matching field is explicitly marked shared with the same `sharedRationale`.
- Preflight expected-binding validation now also rejects malformed canonical binding refs before any rendered-manifest checks run: internal secret refs must stay on `secret://<namespace>/<name>`, certificate refs must stay on the documented `cert-manager://...` shapes, external binding refs must stay on `<scheme>://<namespace>/<binding>`, and `dev-tools/tests/preflight-contract.sh` now proves those fail-closed messages directly.

Remaining work:

- The traffic-open backup gates now validate the evidence record shape, operator identity, and referenced preflight report contract for both environments, but real environment evidence files still need to be produced by operators or automation before first live traffic.
- The preflight expected-binding validation now checks canonical binding-ref syntax, exact rendered Secret and image-pull binding identity, cross-environment external-binding isolation, and referenced preflight-report consumption, but it should continue to tighten against richer Kubernetes live-state evidence as that becomes available.

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Why This Slice Exists

The environment and secrets architecture has the right target-state shape, and the first implementation pass closed the largest split between documented gates and implemented proof. This slice remains open because the proof still needs to harden beyond the initial schema/report checks:

- expected-binding manifests now carry the first required binding identity fields, but validation should become stricter as richer Kubernetes live-state evidence is available;
- `dev-tools/deploy/preflight.py` now consumes those manifests and emits `expectedBindingsRef`, but later checks should validate more of the rendered/live environment rather than only the first manifest shape;
- the documented policy IDs now have first executable coverage, but real traffic-open evidence files still need to be produced by operators or automation before first live traffic;
- player-facing JWT file-mounted secrets, mounted JWKS resources, and preview-unique JWT/JWKS rendering are live first passes, but rotation and operational evidence still need continued proof.

This slice exists so the repo does not treat a first-pass preflight implementation as complete deployment safety.

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Scope

- Continue validating the `expected-bindings.yaml` schema for `hobby-self-hosted`, staging, and production, including storage binding identity fields, concrete workload/bridge/control-plane certificate refs, operator bindings, and service-discovery override policy.
- Keep `dev-tools/deploy/preflight.py` aligned with the environment manifest and `expectedBindingsRef` report contract.
- Keep the required policy IDs executable: `PREFLIGHT-SECRETS-002`, `PREFLIGHT-BOOTSTRAP-001`, `PREFLIGHT-EXTERNAL-001`, `PREFLIGHT-SERVICES-001`, `PREFLIGHT-BACKUP-002`, and `PREFLIGHT-BACKUP-003`.
- Keep player-facing JWT startup on the file-mounted signing material contract for canonical non-test runtimes.
- Keep Account Service JWKS publication aligned to the mounted `jwt-jwks` resource for Kubernetes-backed environments.
- Keep preview namespace preparation on PR-unique JWT signing material and a matching namespace-local JWKS resource.
- Maintain CI checks that validate expected-binding manifest schema and preflight report shape so future contract drift fails early.

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Out of Scope

- Moving secrets to an external secret manager such as Vault.
- Redesigning the environment class matrix.
- Implementing general-purpose compliance automation beyond the policy IDs listed here.
- Replacing the existing preview Helm chart or Kustomize overlay structure for unrelated reasons.

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Acceptance Shape

- A player-facing preflight report includes every required policy ID with `pass`, `fail`, or `not_applicable` and includes `expectedBindingsRef`.
- Staging, production, and hobby/self-hosted expected-binding manifests validate against the documented schema and include the required binding identity fields.
- A player-facing rendered manifest can run with file-mounted JWT signing material and mounted JWKS resources without requiring inline JWT secrets.
- Preview renders or namespace preparation prove per-namespace JWT/JWKS material rather than inheriting static shared test secrets.
- Production first-live/reopen and hobby/self-hosted first-live/reopen events have executable traffic-open evidence checks instead of manual-only wording.
- CI fails when the preflight policy list, expected-binding schema, or report shape drifts from the architecture contract.

##### source-02-15-8-task-list-environment-preflight-and-secret-binding-convergence-vertical-slice-1-71: Follow-On

- If operators later need stricter credential lifecycle automation, add a separate secret-rotation orchestration slice rather than expanding this convergence slice into a broad compliance program.
<!-- /migration-source -->

### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78

#### `02.15.8.1` External Binding Isolation Preflight Follow-Through - Hosted preview, transport, deployment, or preflight operations (source lines 1-78)

##### Preserved Source Text: source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78

<!-- migration-source path="design/project-management/vertical-slices/02.15.8.1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice.md" lines="1-78" sha256="c40a21fee25a8ee5b71da927a3f95d2f19bfed2dee03282a85ccf4b4bc5bd9c2" heading-offset="3" -->
#### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: `02.15.8.1` External Binding Isolation Preflight Follow-Through

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Goal and Status

Goal: tighten player-facing deploy preflight so external binding validation proves cross-environment isolation from the expected-binding manifests themselves instead of relying on environment-token naming heuristics. Status: complete at the current bounded boundary.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Why This Slice Exists

`02.15.8` already made expected-binding manifests the canonical contract for player-facing preflight, but one meaningful gap remained in the executable proof:

- `PREFLIGHT-EXTERNAL-001` still treated external-binding isolation mostly as a naming heuristic;
- duplicate backup/object-store, asset-store, SMTP, webhook-target, or operator-credential values could still slip through if they looked environment-shaped enough;
- the architecture already said shared external bindings must be explicit and justified, but the live preflight script was not yet enforcing that rule.

This follow-through closes that gap without broadening into live cluster inventory or full secret-lifecycle automation.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Implementation Notes

- `dev-tools/deploy/preflight.py` now validates external player-facing bindings against all player-facing expected-binding manifests, not only the current target manifest in isolation.
- The external-binding check now covers:
  - backup storage bucket and credential binding identity;
  - asset storage bucket, endpoint, and credential binding identity;
  - outbound SMTP host and webhook-target classes;
  - operator credential binding identity.
- Matching external values across player-facing environments now fail preflight unless every matching field is explicitly marked shared with the same non-empty `sharedRationale`.
- The contract now supports that explicit shared-field shape for external bindings through object values such as `value` or `bindingRef` plus `shared: true` and `sharedRationale`.
- `dev-tools/tests/preflight-contract.sh` now proves both sides of that contract:
  - duplicate external binding values are rejected;
  - deliberately shared values with matching rationale are allowed.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Scope

- player-facing external-binding validation inside `dev-tools/deploy/preflight.py`;
- the contract test harness for that preflight behavior;
- architecture and parent-slice docs describing the explicit shared-binding rule.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Out of Scope

- live `kubectl` evidence for external bindings;
- broader secret rotation or credential lifecycle automation;
- changes to the existing player-facing expected-binding manifests beyond preserving compatibility with the tightened rule.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Locked Direction

- player-facing external bindings must be environment-unique by default;
- when an external binding is intentionally shared, the manifests must say so explicitly and consistently;
- preflight must derive this isolation proof from the canonical expected-binding manifests, not from ad hoc naming conventions.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Planned Work

###### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: 1. External Isolation Proof

- [x] Replace the heuristic external-binding environment-token check with cross-manifest isolation validation.
- [x] Include outbound communications and operator credential bindings in that same proof.
- [x] Support explicit shared-field declarations with required rationale.

###### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: 2. Contract Proof and Docs

- [x] Add contract coverage for duplicate rejection and explicit shared-field allowance.
- [x] Update architecture and parent-slice docs to describe the enforced rule.
- [x] Re-run the preflight contract script and Markdown/link proof.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Acceptance Shape

- `PREFLIGHT-EXTERNAL-001` fails when player-facing expected-binding manifests reuse external binding values without explicit shared approval;
- explicitly shared external values pass only when every participating manifest carries the same non-empty `sharedRationale`;
- preflight contract proof covers both duplicate rejection and allowed deliberate sharing.

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Validation

- `bash dev-tools/tests/preflight-contract.sh`
- `./gradlew linkCheck lintMarkdown`

##### source-02-15-8-1-task-list-external-binding-isolation-preflight-follow-through-vertical-slice-1-78: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77

#### `02.15.8.2` Service-Discovery Override Preflight Enforcement Follow-Through - Hosted preview, transport, deployment, or preflight operations (source lines 1-77)

##### Preserved Source Text: source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77

<!-- migration-source path="design/project-management/vertical-slices/02.15.8.2-task-list-service-discovery-override-preflight-enforcement-vertical-slice.md" lines="1-77" sha256="e348fab0349de32106f85156117b281f829760efceab2c201810d2993b3b1c1a" heading-offset="3" -->
#### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: `02.15.8.2` Service-Discovery Override Preflight Enforcement Follow-Through

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Goal and Status

Goal: make player-facing and preview/development preflight treat explicit `FIREMUD_SERVICES_*` overrides as a real contract by validating rendered values against declared `allowedOverrides` instead of only checking that the override map exists. Status: complete.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Current Snapshot (2026-06-29)

- This slice is currently `complete`.
- Contract behavior is enforced in `dev-tools/deploy/preflight.py` for `explicit-overrides` mode, with undeclared and mismatched rendered values failing preflight.
- The companion preflight contract harness includes success and failure cases in this branch.
- Accuracy note (2026-06-29): this remains complete; behavior is now contract-enforced in code and covered by script-level cases.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Why This Slice Exists

`02.15.8` already established the expected-binding and preflight contract, and `02.15.8.1` tightened cross-environment external-binding isolation. One bounded preflight gap still remains:

- `serviceDiscovery.mode=explicit-overrides` currently proves only that `allowedOverrides` is present;
- preflight does not yet compare the actual rendered `FIREMUD_SERVICES_*` environment values to the allowed override contract;
- this leaves room for undeclared, misspelled, or drifted service-discovery overrides to pass static proof even though the architecture says those overrides are exceptional and must be explicit.

This is a compact tooling slice, not a broader deployment redesign.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Scope

- `dev-tools/deploy/preflight.py` service-discovery validation for `explicit-overrides`;
- contract tests covering declared, undeclared, and mismatched override values;
- matching architecture/slice-doc updates for the enforced override rule.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Out of Scope

- redesigning service discovery;
- live cluster/service DNS inventory;
- unrelated expected-binding or traffic-open evidence changes.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Locked Direction

- player-facing and preview/service-discovery overrides are exceptional and must be explicitly declared;
- preflight should validate exact rendered override values against the declared allowed override map;
- undeclared or mismatched `FIREMUD_SERVICES_*` overrides must fail preflight.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Planned Work

###### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: 1. Override Contract Enforcement

- [x] Make preflight extract rendered `FIREMUD_SERVICES_*` values and compare them against `serviceDiscovery.allowedOverrides` when `mode=explicit-overrides`.
- [x] Fail when a rendered override is undeclared, missing, or value-mismatched.
- [x] Keep `kubernetes-dns-default` behavior unchanged except for touched proof updates.

###### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: 2. Contract Proof and Docs

- [x] Extend the preflight contract test harness with one passing explicit-override case and failing undeclared/mismatched cases.
- [x] Update the matching deployment/preflight docs only where they currently underspecify the enforced behavior.
- [x] Re-run the preflight contract script plus Markdown/link proof.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Acceptance Shape

- `serviceDiscovery.mode=explicit-overrides` only passes when every rendered `FIREMUD_SERVICES_*` override is explicitly declared with the exact expected value;
- undeclared or drifted service-discovery overrides fail preflight;
- contract proof covers both success and fail cases.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Spark Delegation Notes

- Keep the batch strictly on service-discovery override enforcement.
- Do not widen into unrelated expected-binding or external-binding work.
- Return exact changed files and exact contract cases added.

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Suggested Starting Surfaces

- `dev-tools/deploy/preflight.py`
- `dev-tools/tests/preflight-contract.sh`
- `design/architecture/system-architecture-deploy-preflight-policy.md`

##### source-02-15-8-2-task-list-service-discovery-override-preflight-enforcement-vertical-slice-1-77: Validation

- `bash dev-tools/tests/preflight-contract.sh`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37

#### Kubernetes Deployment-Contract Convergence Vertical Slice - Hosted preview, transport, deployment, or preflight operations (source lines 1-37)

##### Preserved Source Text: source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37

<!-- migration-source path="design/project-management/vertical-slices/02.15.9-task-list-kubernetes-deployment-contract-convergence-vertical-slice.md" lines="1-37" sha256="111baa983f307477c39e7fa260ebd00fdd87120fe0973f9063b7d1a82a7e2340" heading-offset="3" -->
#### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Kubernetes Deployment-Contract Convergence Vertical Slice

##### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Goal and Status

Converged the checked-in Kubernetes deployment surfaces on one coherent contract by making the player-facing Kustomize path genuinely canonical for staging/production, aligning support assets with that decision, and removing the hosted preview/dev-demo network-policy parity gap. Status: completed.

##### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Why This Slice Exists

This slice existed because the repo had one exercised hosted Helm path and one nominally canonical Kustomize path, but the player-facing overlays still rendered placeholder secret/TLS content and the hosted chart lacked checked-in network-policy parity.

##### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Scope

- Make `k8s/overlays/stage` and `k8s/overlays/prod` the canonical player-facing deployment path.
- Add immutable image/digest control to those overlays.
- Remove placeholder player-facing credentials and TLS material from the rendered canonical path.
- Reconcile support assets with the same secret/config contracts as the rest of the path.
- Add hosted preview/dev-demo `NetworkPolicy` parity through the Helm chart.

##### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Out of Scope

- Replacing Helm with Kustomize or vice versa.
- Reworking unrelated service internals.
- General cluster-capacity or preview-topology redesign.
- Secret-manager migration beyond the current checked-in Kubernetes contract.

##### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Delivered Outcome

- The repo now states one clear truth about the player-facing Kustomize path: it is the canonical staging/production deployment lane.
- Checked-in player-facing manifests no longer rely on placeholder credentials/TLS resources in the rendered canonical path; those bindings are environment-owned and enforced through expected-bindings plus preflight.
- Supporting backup assets use the same secret/config contract as the rest of the player-facing path.
- Hosted preview/dev-demo now render checked-in baseline internal-service `NetworkPolicy` resources from the Helm chart.
- A reviewer can understand the Kubernetes deployment families from docs without reopening contradictory folders and workflows to determine which path is real.

##### source-02-15-9-task-list-kubernetes-deployment-contract-convergence-vertical-slice-1-37: Notes

- This slice intentionally did not change the hosted preview clean-redeploy lifecycle.
- JWT/JWKS lifecycle and rotation-proof follow-up remains owned by `02.15.8`.
<!-- /migration-source -->

### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71

#### Local Reset and Bootstrap Proof Tooling Vertical Slice - Local reset and bootstrap proof tooling (source lines 1-71)

##### Preserved Source Text: source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71

<!-- migration-source path="design/project-management/vertical-slices/02.17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice.md" lines="1-71" sha256="8481e49aeef7dbdae8aaab3ad107a3baf05976f492411b0d28c844ef5dac90c0" heading-offset="3" -->
#### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Local Reset and Bootstrap Proof Tooling Vertical Slice

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Goal and Status

Add a canonical local reset-and-rebuild toolchain that can destroy local runtime state, rebuild the stack from migrations and seed/bootstrap state, and prove a narrow gameplay happy path without relying on hand-driven cleanup. Status: complete through `02.17.1` to `02.17.3`.

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Why This Slice Exists

Preview and full-smoke work has already exposed the kinds of startup and migration problems that are cheap to fix now and expensive to discover later:

- duplicate Flyway migration versions
- shared history-table collisions
- bootstrap state that only works when old volumes are already populated
- preview-only cleanup flows that local developers cannot easily reproduce

FireMUD should have one boring, repeatable local operator/developer path that proves:

- the stack boots from zero
- migrations apply cleanly
- seed/bootstrap state is deterministic enough for narrow proof
- later pre-launch schema cleanup can be tested safely before `06`

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Scope

- One canonical local clean-reset stack script
- One canonical fresh-bootstrap verification path
- Shared assumptions with the current smoke-stack compose flow where practical
- Explicit destructive behavior and local-only scope
- Follow-on decomposition for service-scoped reset and migration sanity checks

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Out of Scope

- Full production backup/restore workflows
- Arbitrary historical upgrade-path replay from old releases
- General-purpose cluster reset tooling
- Replacing preview proof work

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Architecture Notes

- Local reset tooling should reuse the current compose/smoke stack contract rather than inventing a second startup model.
- Tooling must be Flyway-aware now that services can own separate migration history tables.
- “Fresh bootstrap from zero” and “restart against existing state” are different proofs and should be tracked separately, not conflated.
- Scripts should stay one-shot and operator-friendly:
  - reset the stack
  - reset one service DB
  - verify fresh bootstrap

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Acceptance Shape

- A developer can run one command to destroy local stack state and rebuild from zero.
- A developer can run one command to verify narrow fresh-bootstrap correctness, not just container liveness.
- The toolchain explicitly states what local data it destroys.
- The current smoke/preview failures around startup-state drift become reproducible locally without bespoke manual steps.

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Implementation Notes

The parent slice is now satisfied by its child cuts:

- `02.17.1` landed the service-scoped DB reset contract and Flyway-aware cleanup tooling.
- `02.17.2` landed the canonical fresh-bootstrap, restart-state, and smoke-image proof entrypoints.
- `02.17.3` landed the Flyway migration sanity checker and CI guardrail.

##### source-02-17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice-1-71: Follow-On

- Keep future smoke/bootstrap improvements in the child slice family or later environment-proof slices rather than reopening the parent framing slice.
<!-- /migration-source -->

### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34

#### Service DB Reset and Flyway Hygiene Vertical Slice Task List - Database reset and Flyway hygiene tooling (source lines 1-34)

##### Preserved Source Text: source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34

<!-- migration-source path="design/project-management/vertical-slices/02.17.1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice.md" lines="1-34" sha256="5a9dbf8516bc2c2c895f828eaf8d19be80c5ae10633b07f02bd59cb55680e367" heading-offset="3" -->
#### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34: Service DB Reset and Flyway Hygiene Vertical Slice Task List

##### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34: Goal and Status

Goal: add bounded local tooling to reset one service’s database state cleanly, including its owned Flyway history, so migration work can be tested without wiping the whole stack. Status: implemented.

This slice follows `02.17` and focuses on service-scoped destructive reset behavior rather than full-stack bootstrap proof.

##### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34: Scope

- Service-scoped local DB reset tooling
- Flyway-history-aware cleanup per service
- Clear destructive-scope messaging
- Alignment with the current compose Postgres layout

##### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34: Key Tasks

- [x] Add a `dev-tools/restores/reset-service-db.sh <service>` style entrypoint.
- [x] Map supported services to their owned tables/history-table contract instead of blindly truncating the whole database.
- [x] Ensure the reset flow clears both:
  - service-owned runtime tables
  - the matching Flyway history table for that service
- [x] Make the command fail clearly when a service does not have a known DB-reset contract.
- [x] Document the exact destructive scope for operators/developers.

##### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34: Tests

- [x] Add a focused validation path that proves the reset service can rebuild from migrations.
- [x] Ensure the tooling catches wrong or incomplete Flyway reset behavior before startup smoke.

##### source-02-17-1-task-list-service-db-reset-and-flyway-hygiene-vertical-slice-1-34: Notes

- This slice is intentionally local/dev focused.
- The goal is not “generic database surgery”; it is a safe, explicit reset tool for current FireMUD services.
<!-- /migration-source -->

### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40

#### Fresh Bootstrap and Restart Proof Tooling Vertical Slice Task List - Fresh-bootstrap and restart proof tooling (source lines 1-40)

##### Preserved Source Text: source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40

<!-- migration-source path="design/project-management/vertical-slices/02.17.2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice.md" lines="1-40" sha256="0e4ce57e8aaad6bbae3c55bf1ed1ff9f234954048fc3735e05a4197718ab62aa" heading-offset="3" -->
#### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40: Fresh Bootstrap and Restart Proof Tooling Vertical Slice Task List

##### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40: Goal and Status

Goal: add one fresh-bootstrap proof script and one restart-state proof script so local/runtime robustness can be validated explicitly rather than inferred from ad hoc boot success. Status: implemented.

This slice follows `02.17` and assumes the local reset contract is already defined.

##### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40: Scope

- Fresh-bootstrap verification from zero local state
- Restart-state verification against existing local state
- Narrow gameplay happy-path proof
- Deterministic enough seed/bootstrap validation for the active stack

##### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40: Key Tasks

- [x] Add a `dev-tools/verify-fresh-bootstrap.sh` entrypoint that:
  - resets the local stack
  - starts the stack cleanly
  - verifies a narrow gameplay path such as `LOGIN -> PLAY -> LOOK`
- [x] Add a `dev-tools/verify-restart-state.sh` entrypoint that:
  - starts from existing local state
  - restarts services without wiping volumes
  - proves the stack tolerates already-populated state
- [x] Add a dedicated image-tag smoke proof entrypoint so local smoke-image validation does not depend on ad hoc shell exports or manual `docker/.env` handling:
  - `SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh`
- [x] Reuse existing smoke-stack/runtime assumptions where practical so local verification matches CI behavior.
- [x] Ensure the verification path checks correctness signals, not just “all containers are up”.

##### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40: Tests

- [x] Add CI-friendly validation or script tests where practical.
- [x] Ensure the scripts fail loudly on missing bootstrap data or incomplete startup.

##### source-02-17-2-task-list-fresh-bootstrap-and-restart-proof-tooling-vertical-slice-1-40: Notes

- Fresh bootstrap and restart-state proof should remain separate commands.
- Smoke-image validation should also have one canonical command rather than relying on manual compose interpolation knowledge.
- The first gameplay proof can stay narrow; the point is robustness, not broad gameplay coverage.
<!-- /migration-source -->

### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69

#### Pre-v1 Migration Squash and Baseline Reset Vertical Slice - Pre-v1 migration baseline reset (source lines 1-69)

##### Preserved Source Text: source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69

<!-- migration-source path="design/project-management/vertical-slices/02.17.2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice.md" lines="1-69" sha256="dd887b11103dc3a0b232d1026ff79b9666afbd5929954cd03d1cdf07b43e26d1" heading-offset="3" -->
#### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Pre-v1 Migration Squash and Baseline Reset Vertical Slice

##### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Goal and Status

Goal: restate the busiest service schemas as clean pre-v1 baselines instead of continuing to carry long Flyway archaeology chains now that the service-local reset tooling from `02.17.1` exists. Status: complete at the current target boundary.

This slice is the owning follow-through for destructive migration squashes. It exists so architecture-audit cleanup slices can identify the need for squashing without also owning risky baseline rewrites directly.

##### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Scope

- choose the concrete service baselines to squash first;
- use the existing service-scoped reset workflow from `02.17.1`;
- replace long migration chains with one canonical current baseline per chosen service;
- prove fresh boot and local reset against the squashed baseline;
- remove superseded migration archaeology once the new baseline is live.

##### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Initial Target Services

- `game-session-service`
- `automation-scripting-service`
- `entity-management-service`

These are the busiest migration families called out by the 2026-05-16 audit and the highest-value pre-v1 squash candidates.

##### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Implementation Notes

- `entity-management-service` is now the first completed squash target. Its historical local migration chain has been replaced with one canonical `V1__baseline.sql` that matches the live pre-v1 schema, and the superseded local migrations were removed instead of keeping both baseline and archaeology paths live.
- `automation-scripting-service` is now the second completed squash target. Its historical local migration chain has also been replaced with one canonical `V1__baseline.sql` that matches the live pre-v1 schema, and the superseded local migrations were removed instead of carrying both the baseline and the old chain forward together.
- `game-session-service` is now the third completed squash target. Its historical local migration chain has also been replaced with one canonical `V1__baseline.sql` generated from the live migrated pre-v1 schema, and the superseded local migrations were removed instead of leaving a mixed baseline-plus-archaeology path in place.
- The `02.17.1` reset tooling was widened in the same batch because `entity-management-service` also imports shared saga migrations. `dev-tools/restores/reset-service-db.sh` now:
  - includes shared saga tables in the destructive scope for saga-backed services;
  - waits for the local Postgres container to become ready before issuing drops; and
  - exports the standard `FLYWAY_URL` / `FLYWAY_USER` / `FLYWAY_PASSWORD` environment variables so the Gradle Flyway tasks can rerun migrations reliably after the drop step.
- The same reset workflow now also exports and uses the service-local schema plus Flyway history table (`SERVICE_SCHEMA`, `SPRING_FLYWAY_TABLE`, `FLYWAY_SCHEMAS`, `FLYWAY_DEFAULT_SCHEMA`, `FLYWAY_TABLE`) instead of silently rerunning local Gradle Flyway tasks against `public` and the default `flyway_schema_history` table.
- Root Gradle Flyway wiring is also now hardened for local destructive reset and ERD generation: the PostgreSQL Flyway database module is on the buildscript classpath so `:service:flywayMigrate` works outside Spring Boot startup too.

##### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Key Tasks

- [x] Freeze the exact owned-table and Flyway-history contract for each target service before rewriting baselines.
- [x] Produce one canonical squashed baseline migration for `game-session-service`.
- [x] Produce one canonical squashed baseline migration for `automation-scripting-service`.
- [x] Produce one canonical squashed baseline migration for `entity-management-service`.
- [x] Delete or archive the superseded historical migration chains for the squashed services instead of keeping both paths live.
- [x] Run fresh-boot proof and service-scoped reset/rebuild proof for each squashed service using the `02.17.1` tooling.
- [x] Update architecture and slice docs so the new baseline history is the only documented current path.

###### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Progress Notes

- `entity-management-service`
  - [x] Restated the final local schema as one canonical baseline migration.
  - [x] Removed the superseded local migration chain.
  - [x] Proved service-scoped destructive reset/rebuild against the new baseline.
  - [x] Proved `:entity-management-service:check -PfullCheck` against the new baseline.
- `game-session-service`
  - [x] Restated the final local schema as one canonical baseline migration.
  - [x] Removed the superseded local migration chain.
  - [x] Proved service-scoped destructive reset/rebuild against the new baseline.
  - [x] Proved `:game-session-service:check -PfullCheck` against the new baseline.
- `automation-scripting-service`
  - [x] Restated the final local schema as one canonical baseline migration.
  - [x] Removed the superseded local migration chain.
  - [x] Proved service-scoped destructive reset/rebuild against the new baseline.
  - [x] Proved `:automation-scripting-service:check -PfullCheck` against the new baseline.

##### source-02-17-2-task-list-pre-v1-migration-squash-and-baseline-reset-vertical-slice-1-69: Notes

- This is intentionally destructive pre-v1 simplification work.
- Do not preserve phased compatibility chains just to avoid rewriting old migrations.
- Keep the execution here coordinated rather than sneaking one-off migration rewrites into unrelated feature slices.
<!-- /migration-source -->

### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35

#### Flyway Migration Sanity Checks Vertical Slice Task List - Flyway migration sanity tooling (source lines 1-35)

##### Preserved Source Text: source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35

<!-- migration-source path="design/project-management/vertical-slices/02.17.3-task-list-flyway-migration-sanity-checks-vertical-slice.md" lines="1-35" sha256="2a065f61ebdf807f2199307821194162932ef24e955d3b2972893427625ebb1d" heading-offset="3" -->
#### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Flyway Migration Sanity Checks Vertical Slice Task List

##### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Goal and Status

Goal: add lightweight tooling and CI validation that catches obvious migration-shape problems such as duplicate versions before compose/preview startup does. Status: implemented.

This slice follows `02.17` and is intentionally narrow: it is about fast failure on migration hygiene, not full upgrade replay.

##### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Implementation Notes

- The canonical local entry point is `./gradlew checkFlywayVersions`, which runs `dev-tools/validation/check-flyway-versions.sh`.
- CI runs the same check in the `Flyway Migration Sanity Checks` workflow job.

##### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Scope

- Duplicate Flyway version detection
- Per-service migration directory sanity checks
- Local developer invocation and CI invocation

##### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Key Tasks

- [x] Add a `dev-tools/validation/check-flyway-versions.sh` style script that scans service migration directories.
- [x] Fail on duplicate versions within a service migration set.
- [x] Emit service-scoped, human-readable failure output.
- [x] Wire the check into CI so obvious migration-shape regressions fail earlier than full smoke/bootstrap.

##### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Tests

- [x] Add a validation path for the checker itself.
- [x] Ensure CI fails fast on deliberately duplicated migration versions.

##### source-02-17-3-task-list-flyway-migration-sanity-checks-vertical-slice-1-35: Notes

- This slice is not a full upgrade simulator.
- It exists because migration-shape errors are cheap to detect statically and expensive to discover during full stack boot.
<!-- /migration-source -->

### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83

#### Local Gradle Proof Reliability Vertical Slice - Local Gradle proof reliability (source lines 1-83)

##### Preserved Source Text: source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83

<!-- migration-source path="design/project-management/vertical-slices/02.17.4-task-list-local-gradle-proof-reliability-vertical-slice.md" lines="1-83" sha256="077ff5704a1452238baba5c4ea2186fb096cfff42f6edffb316a62c27fc05a6f" heading-offset="3" -->
#### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Local Gradle Proof Reliability Vertical Slice

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Goal and Status

Goal: make local Gradle verification fail for real product regressions rather than hanging quietly after green suites or surfacing corrupted shared test-result state from overlapping reruns. Status: complete at the current operator-proof boundary.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Why This Slice Exists

The current `02.17` tooling family already gives FireMUD canonical local proof entrypoints, but the Gradle side of that proof still has two recurring reliability gaps:

- larger service `:check -PfullCheck` runs can go quiet after writing fresh green XML result files, which makes it hard to tell whether the repo is still doing meaningful work or only lingering in teardown/reporting;
- overlapping or too-soon rerun test tasks against the same module can corrupt `build/test-results/**/binary` state and surface `EOFException`/`NoSuchFileException` noise that looks like a source regression even when the real suites already passed.

This slice is not about changing product behavior. It is about tightening the repo-owned local verification contract so developers and AI workers stop losing time to ambiguous Gradle tail phases and shared result-directory corruption.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Scope

- repo-owned guidance or helper tooling for distinguishing meaningful long-tail Gradle work from already-green proof;
- repo-owned guardrails or wrapper behavior for module reruns that would otherwise collide on shared test-result directories;
- validation/docs updates for the canonical local proof path when those guardrails land.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Out of Scope

- redesigning Gradle itself or replacing the current service/task layout;
- remote CI scheduling;
- broad build-performance work unrelated to local proof trustworthiness.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Locked Direction

- local proof should stay Gradle-native rather than inventing a parallel ad hoc test runner;
- repo tooling should detect or prevent the misleading states that have already wasted real slice time;
- when the actionable proof is already green, the operator experience should say so explicitly instead of forcing guesswork from a quiet wrapper process;
- overlapping reruns against one module should either be serialized/guarded or made to clean their result directories before the next gate is trusted.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Planned Work

###### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: 1. Quiet Long-Tail Gradle Detection

- [x] Reproduce one representative service-check case where fresh green test XML exists while the wrapper remains alive quietly afterward.
- [x] Decide whether the right repo-owned contract is a wrapper/helper, documented inspection flow, or a narrower Gradle-task adjustment.
- [x] Land the minimal local-proof improvement that makes the long-tail state distinguishable from a real failing validation run.

###### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: 2. Shared Test-Result Collision Guardrails

- [x] Reproduce the current `results-generic.bin` / `in-progress-results-generic.bin` corruption path with overlapping or too-soon reruns against the same service.
- [x] Add the smallest repo-owned guardrail that prevents trusting corrupted mixed result directories on rerun.
- [x] Keep the fix local-proof-focused rather than widening into unrelated CI/build redesign.

###### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: 3. Validation Contract Updates

- [x] Update the canonical local-proof docs or helper output so the expected rerun/cleanup/inspection flow is explicit.
- [x] Add any focused script or contract proof that locks the chosen behavior where practical.
- [x] Re-run the touched validation/documentation proof.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Acceptance Shape

- a quiet post-suite Gradle tail can be recognized or surfaced clearly without treating it as an automatic source bug;
- overlapping or repeated local verification against one module no longer produces misleading corrupted-result failures that look like product regressions;
- the repo documents or automates one canonical response when these local-proof states occur.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Implementation Notes

- `dev-tools/validation/run-locked-gradle.sh` now wraps `./gradlew` with repo-owned verification locks derived from the requested Gradle task scope:
  - service-scoped tasks such as `:game-session-service:check -PfullCheck` acquire one service lock;
  - unscoped or mixed-scope tasks fall back to one repo-wide verification lock;
  - lock owner metadata is written under `.gradle/firemud-validation-locks/` so a blocked rerun can report which local process already owns the proof lane.
- `dev-tools/validation/inspect-test-results.sh <service>` now provides a read-only summary of parsed JUnit XML under `services/<service>/build/test-results`, including per-suite totals, latest result timestamps, and any failing XML files.
- The repo guidance now treats those tools as the canonical response:
  - use the locked runner for heavy local verification;
  - use the XML inspector when a Gradle run goes quiet after test execution;
  - do not guess at task completion by teaching the runner to auto-kill or auto-pass a quiet Gradle process.

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Suggested Starting Surfaces

- `dev-tools/`
- service `build.gradle.kts` test-task configuration where a narrow fix is justified
- `AGENTS.md`
- `design/project-management/vertical-slices/02.17-task-list-local-reset-and-bootstrap-proof-tooling-vertical-slice.md`

##### source-02-17-4-task-list-local-gradle-proof-reliability-vertical-slice-1-83: Validation

- `bash dev-tools/tests/gradle-proof-tooling-contract.sh`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98

#### Fresh Bootstrap Image Freshness and Smoke Transport Convergence Vertical Slice - Fresh-image and smoke transport proof (source lines 1-98)

##### Preserved Source Text: source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98

<!-- migration-source path="design/project-management/vertical-slices/02.17.5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice.md" lines="1-98" sha256="58ff434793074e9a507016dedeef034526e4d77dff2e49b7ae40dda878011988" heading-offset="3" -->
#### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Fresh Bootstrap Image Freshness and Smoke Transport Convergence Vertical Slice

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Goal and Status

Goal: make the canonical source-built smoke proof always boot the intended fresh service images and converge local/hosted smoke transport behavior beneath the already-shared command catalogs. Status: complete at the current bounded boundary.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Why This Slice Exists

`02.17.2` and later smoke/tooling work already established one canonical gameplay smoke entrypoint and shared command-step catalogs through `dev-tools/smoke/smoke_common.py`. Two bounded but still real proof gaps remain:

- `bash dev-tools/verify-fresh-bootstrap.sh` can still reuse stale compose images even after local boot jars changed, which means container logs can disagree with the current packaged artifact on disk;
- the local websocket smoke, local telnet smoke, and hosted telnet smoke share command plans but still keep separate transport read/drain/send loops and partial retry behavior, so proof can drift below the scenario layer.

This slice keeps the scope narrow: it is about the trustworthiness of the canonical smoke proof, not a broader gameplay or transport redesign.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Scope

- source-built smoke image freshness in `dev-tools/verify-fresh-bootstrap.sh` and any helper it depends on;
- shared smoke transport executor behavior across the current local websocket, local telnet, and hosted telnet proof surfaces;
- narrow documentation/proof updates for the canonical smoke contract.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Out of Scope

- broad new smoke scenarios;
- replacing black-box smoke with in-process test harnesses;
- unrelated Docker or hosted deployment changes outside the touched proof path.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Locked Direction

- the canonical fresh-bootstrap proof must actually boot the code that was just built;
- if compose caching remains allowed anywhere, the script must make cache/freshness behavior explicit rather than accidental;
- canonical smoke flows should share both command catalogs and transport executor semantics when they are proving the same user-visible path;
- transport-specific exceptions should stay explicit and rare instead of living as silent script drift.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Planned Work

###### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: 1. Fresh Image Proof Correctness

- [x] Reproduce the stale-image path against the current jar-context compose build flow.
- [x] Decide whether the canonical fix is unconditional `--no-cache`, touched-service selective no-cache rebuilds, or an equivalent repo-owned freshness check.
- [x] Land the smallest change that makes `verify-fresh-bootstrap.sh` trustworthy for recently changed services.

###### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: 2. Smoke Transport Executor Convergence

- [x] Audit the current websocket local, telnet local, and telnet hosted smoke loops and identify the exact transport behaviors that still drift.
- [x] Move the shared executor semantics into `dev-tools/smoke/smoke_common.py` or an adjacent shared helper instead of leaving them script-local.
- [x] Keep only the intentionally transport-specific pieces outside the shared helper.

###### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: 3. Canonical Smoke Contract Refresh

- [x] Update the touched tooling/docs so the repo teaches one current smoke-proof contract.
- [x] Re-run the canonical fresh-bootstrap/hosted smoke proof required by the touched change.
- [x] Re-run Markdown/link proof for the updated slice/tooling docs.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Acceptance Shape

- source-built smoke no longer silently boots a stale service image after local jar changes;
- local and hosted canonical smoke paths share one transport-executor contract where they are proving the same gameplay flow;
- the remaining transport-specific smoke behavior is deliberate and documented.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Implementation Notes

- `dev-tools/verify-fresh-bootstrap.sh` now keeps the normal cached compose build path for ordinary runs, but supports explicit targeted freshness through `FIREMUD_SMOKE_NO_CACHE_SERVICES="service-a service-b"` using Docker Compose service ids such as `gateway` or `game-session-service`.
- The no-cache list is validated against the current compose service set and fails fast on unknown names instead of silently doing the wrong thing.
- In serial mode, targeted services build with `--no-cache` in normal compose order.
- In non-serial mode, targeted services are rebuilt first with `--no-cache`, and the remaining services keep the usual parallel cached build path.
- WSL-local Docker smoke proof now assumes a native Linux Docker CLI pointed at `unix:///var/run/docker.sock`; a Windows `docker.exe` wrapper can still answer basic version/build commands while breaking the bind mounts the source-built Compose path depends on.
- Service images now set explicit runtime-readable boot-jar ownership and mode at image build time instead of inheriting host artifact permissions; the Game Session image keeps its rename step but drops back to the non-root `firemud` runtime user after normalizing `/app/app.jar`.
- `dev-tools/smoke/smoke_common.py` now owns one shared transport-session executor for smoke flows, with:
  - one shared telnet session runner used by both local and hosted Telnet smoke entrypoints;
  - one shared websocket session runner used by the local WebSocket smoke entrypoint;
  - explicit retry-window semantics living in the shared helper instead of only the hosted wrapper script.
- The remaining script-local logic is now intentional:
  - local Telnet keeps its pre-readiness blocked-admission proof outside the shared executor;
  - local WebSocket still owns its Game Session header setup;
  - hosted Telnet still owns only hosted-environment labeling and env wiring.
- A dedicated contract check now exercises the shared transport-session helper behavior without needing live sockets.

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Suggested Starting Surfaces

- `dev-tools/verify-fresh-bootstrap.sh`
- `dev-tools/smoke/smoke_common.py`
- `services/game-session-service/websocket-login-look-smoke.sh`
- `services/tcp-proxy-service/telnet-login-look-smoke.sh`
- `dev-tools/hosted/shared/hosted-login-look-smoke.sh`

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Validation

- `bash dev-tools/tests/smoke-transport-contract.sh`
- `bash dev-tools/verify-fresh-bootstrap.sh`
- the relevant hosted smoke proof for any touched hosted helper
- `./gradlew linkCheck lintMarkdown`

##### source-02-17-5-task-list-fresh-bootstrap-image-freshness-and-smoke-transport-convergence-vertical-slice-1-98: Current Validation Note

- `bash dev-tools/tests/smoke-transport-contract.sh`
- `./gradlew linkCheck lintMarkdown`
- `bash dev-tools/verify-fresh-bootstrap.sh` now passes end-to-end again after correcting the local WSL Docker CLI path and making service images normalize boot-jar ownership/mode during image build instead of inheriting host-side `0600` jar permissions.
<!-- /migration-source -->

### source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-3-7-20-21-33-34-40

#### `02.18.13` Runtime Feature Flag Authority Convergence - Operator ingress and audit-surface responsibilities (source lines 3, 7, 20-21, 33-34, 40)

##### Preserved Source Text: source-02-18-13-task-list-runtime-feature-flag-authority-convergence-vertical-slice-3-7-20-21-33-34-40

<!-- migration-source path="design/project-management/vertical-slices/02.18.13-task-list-runtime-feature-flag-authority-convergence-vertical-slice.md" lines="3, 7, 20-21, 33-34, 40" sha256="c3d83fa7e66ed1ba9b6c8ef78591f8976a84adc432d5315a17e3fefb98c96014" heading-offset="3" -->
Goal: converge runtime feature-flag mutation and persistence on one canonical control-plane authority so Logging & Admin is an operator ingress and audit surface, not a second runtime-truth store parallel to Game Session. Status: complete.
<!-- source-gap: lines 4-6 -->
Game Session is now the canonical persisted runtime feature-flag authority. Logging & Admin keeps its operator HTTP/gRPC ingress but forwards toggles to Game Session instead of writing a local `feature_flag` table, and the duplicate Logging & Admin feature-flag entity/repository/mapper/migration/seeding path has been removed.
<!-- source-gap: lines 8-19 -->
- runtime feature-flag ownership and write path
- Logging & Admin as operator ingress and audit surface
<!-- source-gap: lines 22-32 -->
- Logging & Admin is not the long-term persistence owner for runtime feature flags it does not execute.
- operator writes should enter through Logging & Admin and flow to the owning domain authority.
<!-- source-gap: lines 35-39 -->
- Logging & Admin records operator action/audit without becoming a second runtime flag database.
<!-- /migration-source -->

### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-19-23-35-38

#### `02.18.14` Moderation Policy Definition and Enforcement Split - Logging and Admin policy-control ingress (source lines 19-23, 35-38)

##### Preserved Source Text: source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-19-23-35-38

<!-- migration-source path="design/project-management/vertical-slices/02.18.14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice.md" lines="19-23, 35-38" sha256="c51802a5032fa25e8201852a76bda888061f9d49abcf2f0f540b15c23428dd14" heading-offset="3" -->
##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-19-23-35-38: Scope

- moderation policy definition and ownership
- split between:
  - account-security actions
<!-- source-gap: lines 24-34 -->
##### source-02-18-14-task-list-moderation-policy-definition-and-enforcement-split-vertical-slice-19-23-35-38: Locked Direction

- moderation record creation is not itself the enforcement action.
- Logging & Admin is the moderation policy/control-plane ingress, not the destructive executor of every consequence.
<!-- /migration-source -->

### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32

#### Cross-Service Test Fixtures and Shutdown Noise Vertical Slice - Cross-service test fixtures and shutdown proof (source lines 1-32)

##### Preserved Source Text: source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32

<!-- migration-source path="design/project-management/vertical-slices/02.18.16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice.md" lines="1-32" sha256="d02f67ba7c4886a31cc3c1aa39fe07354bcf140bfbc53eda623845f7d7134946" heading-offset="3" -->
#### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32: Cross-Service Test Fixtures and Shutdown Noise Vertical Slice

Goal: make cross-service tests depend on shared canonical fake authorities and quiet expected teardown noise so new service-boundary RPCs fail for real contract drift rather than duplicated test harness gaps. Status: complete.

##### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32: Implementation Notes

`common-test-support` now provides a reusable `AccountRuntimeStubServer` that implements the current canonical Account runtime RPC surface used by Game Session and TCP Proxy gameplay tests, exposes per-suite knobs for authentication identity, gameplay admission, entitlement availability, and realm-access grants, and captures authentication requests for assertion. Game Session websocket/load cross-service suites and the TCP Proxy gameplay bridge suite now use that shared fixture instead of maintaining inline Account gRPC fakes. `common-test-support` also carries a compatibility test that fully categorizes the current `AccountService` method set so proto growth cannot silently bypass the shared runtime fake.

TCP Proxy shutdown-path disconnect noise is now classified more precisely as well: when the Telnet handler is already closing on an event loop that is shutting down, `CANCELLED` and `UNAVAILABLE` disconnect-notify transport failures are metered under an expected-shutdown bucket and logged at debug level instead of warning, while active-path transport failures still surface as warnings and keep the existing transport-failure metric.

##### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32: Problem

Several cross-service suites still define inline Account Service fakes that must be manually updated whenever Account Service grows a canonical runtime RPC such as membership, entitlement, or public-production admission. TCP Proxy cross-service teardown can also emit expected async disconnect transport warnings after nested channels close, making successful runs look suspicious.

##### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32: Scope

- Add shared `common-test-support` fixtures for Account Service cross-service fakes that implement the current canonical Account gRPC runtime surface used by Game Session and TCP Proxy tests.
- Replace inline Account fakes in Game Session and TCP Proxy cross-service suites with the shared fixture, preserving per-test knobs for authentication identity, gameplay admission, entitlement availability, and request capture.
- Add a fixture-level compatibility test that fails when Account Service gRPC adds a runtime method that the shared fake must deliberately implement or explicitly reject.
- Update TCP Proxy/Game Session cross-service teardown so expected post-shutdown disconnect notification cancellation is drained or suppressed without hiding unexpected transport failures during active test execution.

##### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32: Out of Scope

- Changing production Account Service behavior.
- Replacing all service fakes in the repository; this slice targets Account authority fakes first because they are the repeated source of RPC-growth drift.
- Suppressing arbitrary gRPC warnings globally.

##### source-02-18-16-task-list-cross-service-test-fixtures-and-shutdown-noise-vertical-slice-1-32: Validation

- `./gradlew :common-test-support:check`
- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew :tcp-proxy-service:check -PfullCheck`
<!-- /migration-source -->

### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30

#### Canonical Redis Runbook Sequence Vertical Slice - Canonical Redis operations runbook (source lines 1-30)

##### Preserved Source Text: source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30

<!-- migration-source path="design/project-management/vertical-slices/02.18.17-task-list-canonical-redis-runbook-sequence-vertical-slice.md" lines="1-30" sha256="ffa0e3799b06f34b5044d2ad97a70b3ab95ea319f48df2a4fcbc8ca4e2de2ec6" heading-offset="3" -->
#### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30: Canonical Redis Runbook Sequence Vertical Slice

Goal: collapse duplicated Redis reset/recovery command sequences into one normative sequence plus scenario-specific deltas so operational runbooks do not drift. Status: complete.

##### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30: Outcome

- `system-architecture-redis-operations.md#canonical-coordination-reset-sequence` is now the single normative reset/recovery command sequence.
- `system-architecture-redis-reset-and-recovery.md`, `system-architecture-redis-incident-runbook.md`, and backup/restore docs now reference that sequence and keep only scenario-specific scope, session-policy, and evidence notes.
- `dev-tools/tests/architecture-doc-contracts.sh` enforces that these docs continue to point back to the canonical sequence.

##### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30: Problem

Redis operations, reset/recovery, incident, migration, AOF, and scaling docs all reference similar `coordination-maintenance` workflows. When command order changes, duplicated command lists can omit required steps such as preserved-session rebind, command convergence, or maintenance-lock handling.

##### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30: Scope

- Designate one normative Coordination Redis reset/recovery sequence section as the source of truth.
- Refactor scenario runbooks to link to that section by name and list only scenario-specific preconditions, deltas, evidence, and abort paths.
- Keep current-implementation notes near the top of target-state runbooks where `coordination-maintenance` verbs are not fully shipped.
- Add a docs contract check that verifies scenario runbooks reference the canonical sequence instead of restating complete pause/reset/reconcile/converge/init/smoke/resume command blocks.

##### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30: Out of Scope

- Implementing the full `coordination-maintenance` CLI/control-plane surface.
- Changing Redis key semantics or reset policy.

##### source-02-18-17-task-list-canonical-redis-runbook-sequence-vertical-slice-1-30: Validation

- `./gradlew lintMarkdown linkCheck`
- `bash dev-tools/tests/architecture-doc-contracts.sh`
<!-- /migration-source -->

### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164

#### Gameplay Transport Test Harness Convergence Vertical Slice - Gameplay transport test harness (source lines 1-164)

##### Preserved Source Text: source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164

<!-- migration-source path="design/project-management/vertical-slices/02.18.17-task-list-gameplay-transport-test-harness-convergence-vertical-slice.md" lines="1-164" sha256="d0ed1f595bab92de0d33c5c5275956d6b2cc8d46944c96ae6af02301daae8cfe" heading-offset="3" -->
#### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Gameplay Transport Test Harness Convergence Vertical Slice

Goal: converge chained gameplay proof on shared FireMUD-specific transport harnesses so login/play/readiness, disconnect semantics, and multi-actor command flows are expressed once and reused across WebSocket and Telnet tests instead of being rebuilt as bespoke socket loops in each suite. Status: complete at the current boundary.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Implementation Notes

The first half of this slice is now live in `game-session-service`.

- A shared `GameplayWebSocketDriver` now exists in Game Session test fixtures and replaces the earlier duplicated cross-service copy.
- The shared WebSocket driver now owns canonical FireMUD chained-flow behavior rather than only raw transport mechanics:
  - login helpers;
  - `PLAY` helpers with and without explicit character selection;
  - canonical readiness gating through `LOOK`;
  - explicit close-event observation for server-driven logout/invalid-connect-context cases;
  - explicit `abort()` support for unexpected-disconnect/replay-eligibility proof.
- Current Game Session websocket suites now use that shared harness for ordinary chained gameplay flows:
  - login integration;
  - websocket handler integration;
  - communication cross-service proof;
  - LOOK cross-service proof;
  - multiplayer load proof.
- Shared ready-LOOK helpers now carry the same admitted gameplay session assumptions into both websocket integration and cross-service tests, so transcript-shape changes are asserted behaviorally instead of through brittle response-index coupling.

The Telnet half is now materially live at the current boundary.

- `tcp-proxy-service` now has test-fixture support and a first `GameplayTelnetDriver`.
- The smaller `TelnetGatewayGameSessionCrossServiceIntegrationTest` now uses the shared telnet driver.
- The larger `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` now uses the shared telnet driver too, including reconnect, takeover, communication, item/equipment, and multi-actor delivery proof.
- The telnet driver now preserves the old multiline block semantics intentionally: marker detection continues until the full gameplay block or prompt boundary arrives, and `...OrTimeout` helpers keep returning partial/prompt-only transcript truth where that is the behavior under test.
- Shared websocket and telnet scenario helpers now cover ready pair/trio admission flows, so actor/target and actor/target/observer setup no longer needs to hand-roll repeated login/play/readiness choreography in the first migrated suites.
- Shared telnet ready-session helpers now also cover the ordinary ready-gameplay setup inside the larger account/gameplay telnet suite; reconnect and takeover proofs still keep their explicit pre-disconnect transcript setup where that transcript shape is the behavior under test.
- Shared backend assertion helpers now cover the repeated Entity Management item/equipment request-shape proof and the repeated whisper/tell Social Groups request-shape proof, and both the websocket and telnet anchor gameplay suites use them instead of duplicating those checks inline.
- The LOOK websocket cross-service suite no longer carries a private raw `TrackingSocket` reconnect/takeover helper; those reconnect, restart, and takeover proofs now run on the shared gameplay driver plus shared ready-session scenario helper.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Problem

Chained gameplay tests are now important proof for FireMUD because many real regressions only appear after:

- transport connection;
- `LOGIN`;
- `PLAY`;
- gameplay readiness;
- live recipient/session registration;
- downstream service RPCs;
- reconnect/takeover/logout edge behavior.

The repository already has valuable WebSocket and Telnet proof, but too much of it still hand-assembles:

- socket creation;
- guidance banners;
- login/play sequencing;
- readiness waits;
- multiline room/inventory response gathering;
- close/disconnect expectations;
- actor/target/observer setup.

That duplication causes three kinds of drift:

1. tests become flaky because each class invents slightly different readiness or wait semantics;
2. behavior changes require touching many suites just to keep plumbing consistent;
3. transport parity becomes harder to review because WebSocket and Telnet suites prove the same gameplay flow in different local idioms.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Scope

- Provide one shared FireMUD-specific WebSocket gameplay driver in `game-session-service` test fixtures for chained gameplay flows.
- Provide one shared FireMUD-specific Telnet gameplay driver in `tcp-proxy-service` test fixtures for chained gameplay flows.
- Standardize canonical chained-flow helpers for:
  - `LOGIN`;
  - `PLAY`;
  - readiness gating via `LOOK` when the test depends on room/live-session truth;
  - expected server close;
  - unexpected client disconnect.
- Replace duplicated low-level client helpers in relevant chained gameplay suites where the test intent is gameplay behavior rather than raw transport mechanics.
- Extract shared scenario-level helpers where repeated actor/target/observer or replay/readiness setup is the real test shape.
- Leave only genuinely transport-specific tests on raw socket/websocket APIs.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Out of Scope

- Replacing Docker smoke scripts with the same harness; smoke scripts remain black-box operator proof.
- Rewriting unit tests or pure service tests that do not exercise chained gameplay transport behavior.
- General-purpose socket test frameworks; this slice is explicitly FireMUD-specific.
- Non-gameplay transport concerns such as MCP negotiation or generic proxy pipeline byte-level tests unless they also act as chained gameplay proof.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Target State

- WebSocket and Telnet chained gameplay suites use shared FireMUD session drivers rather than duplicating raw transport loops.
- Canonical readiness means one thing in test code and can be reused across suites.
- Ordinary chained gameplay tests read like scenario proof, not transport choreography.
- Logout, reconnect, takeover, and replay tests can still express close semantics explicitly without falling back to entirely bespoke client implementations.
- Backend request-shape assertions remain visible, but the transport/session setup overhead is shared.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Planned Work

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 1. Shared WebSocket Session Driver

- [x] Add a shared `GameplayWebSocketDriver` under Game Session test fixtures.
- [x] Remove the earlier duplicate cross-service-only driver copy.
- [x] Standardize shared login/play helpers.
- [x] Add canonical readiness helpers based on `LOOK`.
- [x] Add server-close observation and explicit client-abort support.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 2. WebSocket Suite Migration

- [x] Move websocket login/integration flows onto the shared driver where they prove chained gameplay behavior.
- [x] Move websocket cross-service communication and LOOK suites onto the shared driver.
- [x] Move first-party websocket handler chained flows onto the shared driver.
- [x] Keep only genuinely transport-specific takeover/close edge cases on lower-level primitives.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 3. Shared WebSocket Readiness and Scenario Helpers

- [x] Move common admitted-session header setup into shared driver helpers.
- [x] Move canonical ready-LOOK entry helpers into shared driver helpers.
- [x] Extract reusable multi-actor scenario helpers for actor/target/observer websocket flows.
- [x] Extract shared replay/reconnect setup helpers where current suites still hand-roll the same shape, while leaving explicit replay/takeover transcript setup in the few suites where that transcript itself is the behavior under test.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 4. Shared Telnet Session Driver

- [x] Enable `tcp-proxy-service` test fixtures for telnet gameplay helpers.
- [x] Add a first `GameplayTelnetDriver` for banner, line-ack, and multiline gameplay block handling.
- [x] Tighten the telnet driver surface until the currently repeated gameplay/account suites can use it without hidden transport-specific assumptions.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 5. Telnet Suite Migration

- [x] Migrate the smaller gateway/game-session telnet cross-service suite first.
- [x] Migrate the larger telnet gameplay/account cross-service suite onto the shared driver.
- [x] Migrate repeated telnet gameplay/login/play/readiness loops onto shared drivers/helpers where the suite is proving canonical gameplay behavior rather than raw line-echo bridge semantics.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 5.1 Shared Telnet Scenario Helpers

- [x] Extract reusable multi-actor scenario helpers for actor/target and actor/target/observer telnet flows.
- [x] Reuse those helpers across the remaining repeated telnet gameplay/login/play/readiness suites outside the two anchor cross-service classes where the helper semantics match the suite intent.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 6. Shared Backend Assertion Helpers

- [x] Extract repeated Entity Management item/equipment request-shape assertions used by both websocket and telnet gameplay loops.
- [x] Extract repeated communication/social request-shape assertions where multiple chained-flow suites verify the same whisper/tell semantics.
- [x] Keep those assertions explicit and readable rather than hiding gameplay intent behind opaque utility layers.

###### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: 7. End-State Cleanup

- [x] Remove superseded local chained-flow helpers once the shared harnesses cover the relevant suites.
- [x] Update slice docs that still describe transport-proof as ad hoc or “still being wired” once the canonical harness is in place.
- [x] Keep this slice doc current with which suites are fully migrated versus still on bespoke transport loops.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Current Remaining Work

- [x] No mandatory follow-up remains inside this slice at the current boundary.
- [x] Keep replay/reconnect/takeover proofs on explicit transcript assertions where the exact pre-disconnect transcript is itself the behavior under test.
- [x] Keep the smaller gateway line-echo telnet bridge suite on explicit line-level assertions where gameplay-ready helpers would hide the behavior under test.
- [x] Small polish completed: consolidated repeated social request-shape assertions in the communication websocket suite into shared `GameplaySocialAssertions` helpers while preserving scenario-facing intent.
- [x] Track broader second-pass proof convergence separately in [`02.18.18`](../vertical-slices/02.18.18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice.md) rather than reopening transport-driver convergence here.
- [ ] Keep future chained gameplay suites on the shared drivers, ready-session helpers, scenario helpers, and backend assertion helpers instead of reintroducing bespoke socket/test-local flows.
- [x] Small polish completed: extracted shared `proxyGatewayDriverFactory(...)` in
  `GameplayWebSocketScenarios` so communication replay/actor-target/observer setup reuses a single
  proxy-connection opening path instead of repeating transport-setup lambdas.
- [x] Small polish completed: added shared telnet gateway bootstrap helper (`openDemoGatewayProxySession`) and migrated
  the remaining bespoke line-echo command-sequence setup in
  `TelnetGatewayGameSessionCrossServiceIntegrationTest` to keep the scenario proof explicit while deduplicating transport framing.

##### source-02-18-17-task-list-gameplay-transport-test-harness-convergence-vertical-slice-1-164: Validation

- `./gradlew :game-session-service:check -PfullCheck`
- `./gradlew :tcp-proxy-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79

#### Gameplay Proof and Cross-Service Fixture Convergence Vertical Slice - Gameplay proof and fixture convergence (source lines 1-79)

##### Preserved Source Text: source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79

<!-- migration-source path="design/project-management/vertical-slices/02.18.18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice.md" lines="1-79" sha256="c4b04fa5a966767debc5067347d080707854538c5c839e6ca65f75be0d9878a3" heading-offset="3" -->
#### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Gameplay Proof and Cross-Service Fixture Convergence Vertical Slice

Goal: finish the second convergence pass on FireMUD gameplay proof so shared transport drivers are no longer the only reusable layer; cross-service stack bootstrapping, reconnect/takeover scenarios, async assertions, gateway websocket probes, and smoke-adjacent helper logic should also converge on one canonical test-support shape instead of remaining split across large suites and scripts. Status: complete; the narrower transcript/baseline hardening follow-up in `02.18.18.1` is now also complete at its current bounded boundary.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Why This Slice Exists

`02.18.17` closed the first major harness pass: shared websocket/telnet gameplay drivers, ready-session helpers, multi-actor admission helpers, and first backend request assertions are now live and in use. The repo is materially better than the earlier bespoke socket-loop state.

What remained after `02.18.17` was no longer “we need a driver.” The inconsistency had moved one level up:

- large cross-service suites still bootstrap nested app stacks ad hoc instead of through one gameplay-oriented stack fixture;
- reconnect/takeover proof still repeats transport/session choreography more than it should;
- async assertion loops for metrics, presence, buffered output, and prompt-tolerant LOOK matching are still duplicated;
- gateway websocket tests still open-code low-level client/latch plumbing instead of using one small gateway probe helper;
- smoke scripts still carry transport-specific inline helper logic that has drifted apart.

This is large enough to track as its own follow-up slice instead of quietly reopening `02.18.17`.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Implementation Notes

The first convergence boundary from `02.18.17` is intentionally preserved:

- shared `GameplayWebSocketDriver` and `GameplayTelnetDriver` are already the canonical transport drivers;
- ordinary login / `PLAY` / ready-`LOOK` chained gameplay flows should keep using those drivers rather than inventing new ones;
- raw low-level transport tests such as telnet byte-pipeline proof or gateway bridge socket-rebind edge behavior remain valid exceptions where the raw protocol itself is the behavior under test.

This slice closes the next honest gap in shared proof orchestration above the transport layer:

- one reusable gameplay cross-service stack fixture now owns repeated nested Game Logic / Game Session / backing-store bootstrapping, migration-dir resolution, suite baseline room fixtures, and common mutable stub reset;
- reconnect/takeover helpers now exist at the scenario-support layer so future gameplay suites do not need to rebuild the same login/disconnect/reconnect scaffolding from scratch;
- the higher-level residual audit items are also closed inside the same lane:
  - `GameplayCrossServiceStack` now exposes a canonical fresh gameplay baseline and shared live-session seeding;
  - multiplayer load proof now uses shared load-oriented baseline/admission helpers instead of carrying its own player-seeding and login/play loops;
  - websocket first-party integration flows reuse a shared connect-context helper instead of repeating JWT/header claim assembly inline;
  - gateway integration suites and the smaller telnet bridge suite reuse one shared reactive bootstrap helper rather than duplicating gateway/upstream holder wiring;
  - smoke scripts now share both execution logic and the canonical gameplay command-step catalog, leaving only transport executors distinct;
  - the legacy image-based `TcpProxyCrossServiceIntegrationTest` outlier has been retired in favor of the canonical telnet gateway suites and smoke proof paths;
- prompt-tolerant transcript matchers and async eventual-assert helpers now live in shared test support instead of websocket/telnet-local copies;
- gateway websocket integration keeps its lower-level intent, but now uses one shared probe helper for repeated websocket-client/session/close-state plumbing;
- smoke scripts now share one Python helper module for account validation and readiness/account-schema convergence instead of forking that logic inline per transport.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Scope

- shared gameplay-oriented cross-service app bootstrap helpers above the current `CrossServiceAppHarness`;
- shared reconnect/takeover scenario helpers for websocket and telnet gameplay proof;
- shared async assertion helpers for metrics, buffered screen output, presence counts, and similar eventual proof;
- shared prompt-tolerant LOOK / move-refresh transcript matchers used across gameplay transports;
- gateway-specific websocket probe helpers for the bridge/look integration suites;
- smoke-adjacent transport helper convergence where it removes duplicated inline Python/session-driving logic without collapsing black-box smoke into unit-style harness code.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Out of Scope

- replacing canonical Docker smoke entrypoints with JUnit harness code;
- collapsing genuinely low-level protocol/bridge tests onto gameplay-ready helpers when the raw transport shape is itself the proof;
- broad generic testing frameworks or opaque DSL layers that hide FireMUD command text and gameplay intent.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Target State

- large gameplay cross-service suites share one canonical nested app/bootstrap fixture instead of locally reassembling ports, properties, migration paths, and stub startup;
- reconnect/takeover gameplay scenarios are expressed once per transport family and reused where intent matches;
- prompt-tolerant transcript matching and common eventual assertions live in shared test support rather than per-suite local helpers;
- gateway integration tests still assert low-level bridge behavior directly, but they stop rewriting websocket client/latch scaffolding;
- smoke scripts remain black-box operator proof but reuse more shared helper logic where the current inline transport code has obviously forked.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Current Remaining Work

- [x] No broad second-pass convergence follow-up remains inside this slice boundary.
- [x] Keep raw telnet byte-pipeline proof and gateway bridge socket-rebind edge tests on transport-level visibility where that is still the behavior under test.
- [ ] Migrate future gameplay suites onto the shared reconnect/takeover helpers when later suites are added that match the canonical helper model cleanly.
- [x] Close the narrower residual gameplay-proof debt that had been tracked in [`02.18.18.1`](../vertical-slices/02.18.18.1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice.md) at the current boundary.
- [x] Keep websocket gameplay suites on canonical transcript-block helpers for `LOOK` and move-refresh assertions.
- [x] Keep the shared gameplay baseline reset responsible for reapplying suite-specific room and social fixtures and restarting seeded runtime identity cleanly.
- [x] Keep the largest websocket/telnet suites on the shared fresh-baseline helper instead of repeating local reset choreography.

##### source-02-18-18-task-list-gameplay-proof-and-cross-service-fixture-convergence-vertical-slice-1-79: Checklist

- [x] Define the second-pass convergence boundary clearly.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85

#### Gameplay Transcript and Baseline Proof Hardening Vertical Slice - Gameplay transcript proof hardening (source lines 1-85)

##### Preserved Source Text: source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85

<!-- migration-source path="design/project-management/vertical-slices/02.18.18.1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice.md" lines="1-85" sha256="4d7bc85a307816879a16a26adabf9c5a08838f88ad57700bc0cfbf912c6125ea" heading-offset="3" -->
#### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Gameplay Transcript and Baseline Proof Hardening Vertical Slice

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Goal and Status

Goal: close the remaining gameplay-proof debt that still sits above the converged transport drivers and shared cross-service stack by making transcript assertions less timing-sensitive and by converging suite reset logic on one canonical baseline helper. Status: complete at the current bounded boundary.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Implementation Notes

- `GameplayWebSocketDriver` now exposes canonical transcript-block helpers so websocket suites can wait on the exact `LOOK` or move-plus-`LOOK` block they need instead of racing live response ordering.
- `LookWebSocketCrossServiceTest` now uses those helpers for initial look, movement, reconnect, and fresh-`PLAY` room assertions.
- `GameplayCrossServiceStack` now preserves suite-specific social baseline fixtures as well as room-entity baseline fixtures, and `freshGameplayBaseline(...)` now resets `game_instances` with `TRUNCATE ... RESTART IDENTITY` so suites that depend on stable seeded runtime ids no longer need bespoke reseed choreography.
- `CommunicationWebSocketCrossServiceTest` now proves that shared baseline reset re-applies the configured friend-presence baseline after suite-local mutation.
- `TelnetGatewayGameSessionAccountCrossServiceIntegrationTest` now uses the shared fresh-baseline helper in `@BeforeEach` instead of hand-rolled Redis/runtime/item reset choreography.
- `GameplayLoadScenarios` now keeps load-proof transport session ids separate from the canonical bootstrap runtime target, so concurrent login/play websocket proofs stay on the admitted baseline instance instead of fabricating per-session bootstrap runtime ids that bypass pointer authority.
- The proof refresh also exposed a real Game Session startup cycle between `TickServiceImpl` and `DefaultDurableRemoteFollowupExecutionService`; that cycle is now broken at the remote-followup executor boundary so the converged websocket/telnet cross-service suites can boot cleanly again.
- `GameSessionWebSocketHandlerIntegrationTest.websocketMoveEnqueuesDurableCommandAfterPlay` now asserts against the mock invocation stream rather than letting Mockito fail early on the expected login bootstrap enqueue, so the proof waits for the `north` gameplay enqueue it actually cares about instead of flapping on unrelated earlier invocations.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Why This Slice Exists

`02.18.17` and `02.18.18` already landed the major proof-harness convergence:

- shared FireMUD-specific WebSocket and Telnet drivers;
- shared gameplay cross-service stack bootstrapping;
- shared prompt-tolerant matchers and common async helpers;
- shared smoke helper logic and hosted proof reuse.

What remains is no longer driver-level duplication. The current honest debt is narrower and more operational:

- some websocket transcript assertions still rely on live ordering that can flap under full-suite load even when product behavior is correct;
- the shared gameplay stack still does not fully own the canonical reset-to-known-state path for every suite baseline;
- some suites still rebuild baseline cleanup choreography inline instead of calling one explicit “fresh gameplay baseline” helper.

That work is worth tracking explicitly instead of leaving it as a lingering observation or pretending the earlier convergence slice already closed every proof-shape gap.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Scope

- harden websocket transcript assertions where suite timing/order still creates false failures;
- finish the canonical shared gameplay baseline helper on `GameplayCrossServiceStack` or the equivalent shared support seam;
- ensure shared stack reset restores suite-specific baseline fixtures instead of one global default;
- remove repeated per-suite cleanup choreography where the shared baseline helper can own it cleanly.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Out of Scope

- replacing intentional low-level transport tests where byte ordering or raw bridge semantics are themselves the behavior under test;
- broad gameplay feature work outside proof/harness infrastructure;
- Docker smoke script redesign beyond the already-shared helper layer.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Locked Direction

- cross-service gameplay suites should read like scenario proof, not fixture-reset choreography;
- shared stack reset must preserve the suite-specific baseline captured at stack construction time rather than resetting everything to one repo-global default;
- transcript assertions should wait for the canonical message block they actually care about instead of relying on opportunistic live ordering under load;
- when a proof needs prompt-only or partial transcript truth, the shared helper should model that deliberately rather than forcing suites back to bespoke loops.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Planned Work

###### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: 1. Transcript Assertion Hardening

- [x] Audit the known websocket transcript-flake seams and identify the assertions that still depend on exact live ordering rather than canonical message arrival.
- [x] Add or refine shared helpers so suites can wait on the intended transcript/message boundary explicitly.
- [x] Migrate the currently flaky websocket cross-service assertions onto that helper shape.

###### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: 2. Suite-Specific Baseline Reset

- [x] Ensure shared gameplay stack reset re-applies the suite-specific baseline fixtures captured at stack construction time.
- [x] Remove any remaining reset path that silently falls back to one hardcoded global default fixture.
- [x] Add focused proof that a chat-specific or otherwise customized baseline survives shared reset correctly.

###### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: 3. Canonical Fresh Gameplay Baseline Helper

- [x] Expose one shared helper that resets mutable stubs, clears replay/buffer state, wipes seeded runtime rows as needed, and reseeds the default running gameplay state when the suite wants the canonical clean baseline.
- [x] Migrate the largest remaining websocket/telnet suites off their local cleanup choreography where the shared helper matches the suite intent.
- [x] Keep only the explicit exceptions where a suite is proving pre-disconnect or pre-reconnect transcript shape and therefore needs bespoke setup.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Acceptance Shape

- known websocket transcript flakes are either eliminated or bounded to the few intentional low-level transport tests;
- shared gameplay stack reset demonstrably preserves suite-specific baselines;
- major gameplay cross-service suites use one canonical clean-baseline helper instead of hand-rolled Redis/runtime/stub reset choreography.

##### source-02-18-18-1-task-list-gameplay-transcript-and-baseline-proof-hardening-vertical-slice-1-85: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63

#### Flyway History Contract and Hosted SQL Proof Cleanup Vertical Slice - Flyway and hosted SQL operational proof (source lines 1-63)

##### Preserved Source Text: source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63

<!-- migration-source path="design/project-management/vertical-slices/02.19.12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice.md" lines="1-63" sha256="93e814728e33f3cb665b32f20c224734cec12d016d02d21c52f32fef063d7fb8" heading-offset="3" -->
#### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Flyway History Contract and Hosted SQL Proof Cleanup Vertical Slice

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Goal and Status

Goal: close the remaining small but real post-`02.19` audit tails around Flyway history-table naming, shared Postgres/Flyway proof coverage, dead local JPA-era runtime knobs, and stale summary-layer slice status so the repo teaches one coherent SQL runtime contract everywhere. Status: implemented.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Why This Slice Exists

The `02.19` family already landed the real architectural convergence: SQL-backed services now use `jOOQ + Flyway`, Hibernate/JPA runtime support is gone, and the shared saga/test-support/build-convention tails were cleaned up in `02.19.11`.

The remaining issues are smaller, but they are still exactly the kind of contract drift an audit will flag quickly:

- shared Postgres-backed test support still does not force the same service-local Flyway history table naming used by Docker Compose and local destructive reset tooling;
- `logging-admin-service` is the migrated SQL-backed outlier without a Postgres/Flyway service-boot proof at service level;
- the canonical local runtime manifest still exports dead JPA-era environment knobs even though the runtime no longer uses them;
- the slice index still teaches the `02.19` family as planned even though the family is already implemented.

This slice is the narrow cleanup pass that makes the repo’s runtime, tooling, test-support, and summary docs line up on one SQL contract.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Scope

- align Flyway history-table naming across runtime, hosted/local manifests, shared Postgres-backed test support, and destructive reset tooling;
- add a real Postgres/Flyway service-boot proof for `logging-admin-service`;
- remove dead JPA/Hibernate environment variables from the canonical local Docker runtime manifest;
- fix `02.19` family status drift in the slice index and any adjacent summary docs touched by that cleanup.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Out of Scope

- new persistence architecture beyond the already-closed `02.19` decision;
- reworking unrelated H2-backed unit or narrow test seams that are not pretending to be the canonical Postgres/Flyway proof path;
- reopening the already-implemented `jOOQ` service migration slices unless a real contract mismatch requires it.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Checklist

- [x] Register the canonical service-local Flyway history table in shared Postgres-backed test support.
- [x] Keep hosted/local runtime manifests and destructive reset tooling aligned with the same Flyway table naming convention.
- [x] Add a Postgres/Flyway service-boot integration proof for `logging-admin-service`.
- [x] Remove dead JPA/Hibernate environment knobs from `docker/docker-compose.yml`.
- [x] Correct `02.19` family status drift in the slice index and any touched summary docs.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Implementation Notes

- Shared Postgres/Flyway test support now registers `spring.flyway.table` in the same service-local `flyway_schema_history_<service_schema>` form already used by Docker Compose, Helm-hosted runtime manifests, and destructive reset tooling.
- Every SQL-backed service base `application.yml` now carries the same `spring.flyway.table` contract so plain service boot no longer falls back to bare `flyway_schema_history` when `SPRING_FLYWAY_TABLE` is omitted.
- `logging-admin-service` now has a real Testcontainers-backed application boot proof over Postgres + Flyway + Redis, including service-schema selection and saga/dashboard boot behavior, instead of the old H2 + `firemud.database.enabled=false` fake ping-only seam.
- The canonical local Docker manifest no longer exports dead Hibernate/JPA environment knobs after `02.19.10`.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Validation Target

At minimum, this slice should close with:

- focused validation for the shared Flyway/test-support path;
- `:logging-admin-service:check -PfullCheck`;
- `./gradlew linkCheck lintMarkdown`;
- the relevant canonical local/hosted manifest validation for the touched deploy/runtime files.

##### source-02-19-12-task-list-flyway-history-contract-and-hosted-proof-cleanup-vertical-slice-1-63: Validation

- `bash dev-tools/tests/reset-service-db-contract.sh`
- `./gradlew :logging-admin-service:integrationTest --tests 'net.firedevops.firemud.loggingadmin.LoggingAdminApplicationIntegrationTest'`
- `./gradlew :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`
- `./gradlew check`
<!-- /migration-source -->

### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137

#### Basic Multiplayer Load Proof Vertical Slice Task List - Multiplayer load proof (source lines 1-137)

##### Preserved Source Text: source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137

<!-- migration-source path="design/project-management/vertical-slices/05.1-task-list-basic-multiplayer-load-proof-vertical-slice.md" lines="1-137" sha256="3c739499782ae76045680111ef7088846039d970b70a3cfa5f68a86bb5386c34" heading-offset="3" -->
#### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: Basic Multiplayer Load Proof Vertical Slice Task List

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: Goal and Status

Goal: add the first small but real multiplayer/load proof so FireMUD demonstrates that multiple players can log in, enter the game, and perform basic gameplay actions concurrently against the real service stack instead of only through single-user happy-path tests. Status: implementation-complete for the bounded first-pass proof. Deterministic cross-service proof is now live in `game-session-service`: 10 concurrent WebSocket clients use generated accounts and game instances to perform `LOGIN -> PLAY -> LOOK` against the real nested service stack, a bounded follow-up proof keeps those clients live long enough to perform one synchronized `north` movement step into the same destination room plus one immediate concurrent destination `LOOK` churn pass, and a second bounded follow-up proof now drives one synchronized 10-player `SAY` burst that proves concurrent acceptance, per-actor transcript delivery, and one social dispatch per player. A second bounded proof also demonstrates mixed transport parity in `tcp-proxy-service`: one telnet client and one gateway-backed websocket client can enter the same runtime and each complete one `north` movement step to the same destination room while the other transport remains live. The `load-testing` module remains future work for a more operator-facing or higher-volume harness.

This slice is intentionally narrow. It is not a full performance program and it is not meant to prove final scalability. It exists to validate the first meaningful concurrent gameplay path with real services, real Redis/database state, and multiple simultaneous clients.

The initial target is deliberately small:

- around 10 concurrent players;
- real `LOGIN` -> `PLAY` -> `LOOK`;
- optional bounded follow-up actions such as one `SAY` burst or one movement step;
- enough assertions to prove multiplayer correctness and expose obvious coordination or shared-state bugs early.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 1. Test Scope and Success Criteria

- [x] Define one canonical first-pass multiplayer scenario using approximately 10 concurrent clients.
- [x] Keep the first proof bounded to the already-live core path:
  - `LOGIN`
  - `PLAY`
  - `LOOK`
  - and optionally one additional lightweight gameplay action such as `SAY` or one movement command.
- [x] Keep the first implemented proof bounded to `LOGIN -> PLAY -> LOOK`; defer extra actions until the baseline concurrent entry path is stable.
- [x] Added one bounded follow-up action after the stable entry baseline: one synchronized `north` movement step across the still-connected clients, followed by one concurrent destination `LOOK` churn pass, with destination-room transcript and persisted room-context assertions.
- [x] Added one bounded communication follow-up action after the stable entry baseline: one synchronized 10-player `SAY` burst across the still-connected clients with per-actor transcript and social dispatch assertions.
- [x] Define the minimum success criteria, including at least:
  - all clients authenticate and enter gameplay successfully;
  - no unexpected disconnects or forced reconnect cycles;
  - bounded latency expectations are recorded, even if they are loose at first;
  - and the resulting room view / communication behavior is sane for multiple simultaneous players.
- [x] Baseline success criteria are now asserted for the first proof:
  - all clients authenticate and enter gameplay successfully;
  - no client receives an unexpected error response;
  - all clients receive the canonical room view;
  - and all active session contexts persist the expected room binding after concurrent entry.
- [x] The first bounded follow-up action now also asserts sane concurrent gameplay progression beyond entry:
  - the same connected clients can issue one synchronized movement command after concurrent entry;
  - each client receives the canonical destination move/look transcript;
  - each client can immediately issue one more concurrent `LOOK` from the destination room without disconnecting or drifting room context;
  - each persisted session context updates to the destination room id;
  - and `gamesession.command.move.invocations` increments as expected.
- [x] The communication follow-up now also asserts sane concurrent multiplayer command handling beyond simple room binding:
  - the same connected clients can issue one synchronized `SAY` burst after concurrent entry;
  - each sender receives the canonical transcript for its own `SAY`;
  - the social dispatch stub records one `CHAT_TYPE_SAY` request per player with the expected canonical content;
  - and `gamesession.command.say.invocations` increments as expected.
- [x] Added loose but explicit latency expectations to keep the proof useful for regression detection rather than only timeout-based failure:
  - concurrent `LOGIN -> PLAY -> LOOK` entry currently must complete within 20 seconds once the clients are released together;
  - concurrent ready-player bootstrap before the follow-up phase currently must complete within 20 seconds;
  - and the synchronized north-move plus destination-`LOOK` churn phase currently must complete within 15 seconds.
- [x] Added one bounded mixed-transport parity proof:
  - one telnet client and one gateway-backed websocket client can enter the same runtime with different characters;
  - both can complete one `north` movement step while sharing the same runtime;
  - both receive canonical destination movement proof on their own transport;
  - and both persisted session contexts converge on the same destination room id.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 2. Data Setup Strategy

- [x] Decide and document the first-pass setup model for accounts/characters needed by the load proof.
- [x] Prefer generated test data over large static fixture packs where feasible, for example:
  - create accounts programmatically during setup;
  - create characters programmatically during setup;
  - and bind them to the test tenant/game world as part of the harness.
- [x] The first proof now uses generated data:
  - generated test usernames map deterministically to generated account ids in a test gRPC account stub;
  - game instances are inserted programmatically during setup;
  - and all clients bind into the same bounded world fixture without a growing static account pack.
- [x] Keep the setup pragmatic for test environments: avoid turning this slice into a full synthetic-world generator if a bounded test fixture helper will do.
- [x] Document whether cleanup is required after each run or whether isolated test databases/tenants make coarse cleanup acceptable.
  - The live proofs use isolated Postgres/Redis test containers plus explicit harness resets (`freshGameplayBaseline`, Redis flush, session/screen-buffer reset), so coarse environment disposal is acceptable and no per-player manual cleanup contract is required.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 3. Client Harness Choice

- [x] Decide whether the first load proof should use:
  - raw WebSocket clients;
  - raw TCP/Telnet clients;
  - or both in separate bounded scenarios.
- [x] The first proof uses raw WebSocket clients against the real Game Session WebSocket path.
- [x] A bounded follow-up proof now also uses one real telnet client plus one gateway-backed websocket client together against the same shared runtime.
- [x] Keep the first version simple and close to real client behavior rather than inventing a specialized fake protocol.
- [x] Document the chosen harness clearly enough that later higher-scale tests can reuse it instead of starting over.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 4. Execution Environment

- [x] Decide where the first multiplayer proof lives operationally:
  - `load-testing` module;
  - cross-service integration harness;
  - or another bounded home.
- [x] The first implementation lives in the `game-session-service` cross-service harness so it stays deterministic and CI-friendly while still exercising the real service stack.
- [x] Keep it runnable in a repeatable local/CI-friendly way for the initial 10-client scenario.
- [x] Document which parts of the stack are expected to be real for the proof:
  - real Game Session / Game Logic / Gateway / TCP Proxy as relevant;
  - real Redis/database;
  - and any acceptable stubbing boundaries if they are truly necessary.
- [x] The first proof uses real nested Game Session / Game Logic / World / Entity services plus real Redis/Postgres test containers, with only account/world/entity fixtures stubbed where the existing cross-service harness already does so.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 5. Initial Assertions

- [x] Add baseline assertions for concurrent `LOGIN` / `PLAY` / `LOOK`.
- [x] Add at least one multiplayer-facing assertion beyond simple connection success, for example:
  - multiple players in the same room see sane occupant counts;
  - or a synchronized `SAY` burst is accepted cleanly across the connected clients.
- [x] The first proof asserts that all 10 session contexts remain active in the same room after concurrent entry, which gives a shared-state correctness check beyond simple connection success.
- [x] The first follow-up proof asserts that one synchronized movement step completes across the already-connected concurrent clients without disconnecting them.
- [x] Ensure failures are diagnosable enough that the slice is useful for concurrency debugging rather than only producing “timed out” noise.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 6. Observability and Metrics

- [x] Record the minimum metrics/logging needed to make the first load proof useful, such as:
  - connection count;
  - login/play success rate;
  - simple latency summaries;
  - and disconnect/error counts.
- [x] The first proof now asserts a bounded `gamesession.command.look.invocations` metric count across the concurrent run.
- [x] Keep the first pass lightweight; this slice is proving basic multiplayer viability, not building a full performance dashboard.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 7. Follow-On Scope Boundary

- [x] Explicitly defer larger-scale or more complex scenarios, such as:
  - hundreds of clients;
  - combat-heavy concurrency;
  - inventory-heavy concurrency;
  - and long-running soak tests.
- [x] Explicitly defer the `load-testing` module and larger-volume scenarios until after this deterministic cross-service proof.
- [x] Capture the likely next increments after the initial 10-client proof, for example:
  - mixed WebSocket/Telnet clients;
  - broader shared-room communication fanout proof beyond per-actor transcript acceptance;
  - move + `LOOK` churn;
  - or later inventory/equipment concurrency after `06`.
- [x] The first next increments are now live as bounded concurrent movement plus immediate destination `LOOK` churn proof, a synchronized 10-player `SAY` acceptance/dispatch proof, and a mixed WebSocket/Telnet shared-runtime movement proof. Later increments remain broader shared-room communication fanout proof, inventory/equipment concurrency after `06`, plus any future higher-volume/operator-facing `load-testing` harness work.

##### source-05-1-task-list-basic-multiplayer-load-proof-vertical-slice-1-137: 8. Final QA Checklist

- [x] Verify the first multiplayer proof runs repeatably and demonstrates real concurrent player entry into gameplay.
- [x] Confirm the slice uses generated or otherwise maintainable test data rather than requiring a growing hand-maintained account/character fixture set.
- [x] Confirm the result is useful as a long-lived regression/validation asset rather than a one-off script.
<!-- /migration-source -->

### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87

#### 08.3.1 Task List: Operator Cutover-Compatibility Readback Vertical Slice - Audited primary runtime or service owner (source lines 1-87)

##### Preserved Source Text: source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87

<!-- migration-source path="design/project-management/vertical-slices/08.3.1-task-list-operator-cutover-compatibility-readback-vertical-slice.md" lines="1-87" sha256="5f97161aa32215ed1f1733be2f9cea55c2fa2a860b585c70ddf84b9b35156094" heading-offset="3" -->
#### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: 08.3.1 Task List: Operator Cutover-Compatibility Readback Vertical Slice

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Goal and Status

Goal: expose the canonical Game Session `ValidateInstanceCutoverCompatibility` proof through Logging & Admin so operators can inspect one bounded source-instance to target-version compatibility result without dropping to gRPC or preparing a durable upgrade record first. Status: complete at the current bounded boundary.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Why This Slice Exists

`08.3` already landed the canonical Game Session preflight seam for cutover compatibility and the durable prepared-upgrade follow-through. One operator-facing gap remained:

- Logging & Admin could prepare and read durable upgrade artifacts, but it could not expose the lighter-weight preflight compatibility read itself;
- operators still needed gRPC access or an unnecessary durable preparation row to inspect one bounded compatibility verdict;
- the existing admission-pointer/version-upgrade operator surface was the natural HTTP home for this proof, so leaving this one read gRPC-only kept the version-upgrade boundary one transport seam short of convergence.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Scope

- Logging & Admin client, service, controller, and DTO support for `ValidateInstanceCutoverCompatibility`;
- tenant-qualified operator REST ingress for one `{tenantId, sourceGameInstanceId, targetVersionId}` compatibility read;
- focused controller/service proof and published OpenAPI coverage for the bounded readback surface.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Out of Scope

- changes to Game Session compatibility evaluation rules or participant attestation logic;
- broader cutover execution workflows, which remain covered by prepared-upgrade and cutover routes;
- dashboard/search UX beyond the bounded read route.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Locked Direction

- Logging & Admin must consume the canonical Game Session compatibility proof directly rather than recomputing participant status locally;
- operator ingress must require tenant access before issuing the control-plane read;
- the readback surface should stay bounded to one source instance and one target version, matching the current Game Session contract exactly.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Planned Work

###### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: 1. Operator Read Surface

- [x] Add a Logging & Admin control-plane client method for `ValidateInstanceCutoverCompatibility`.
- [x] Add a tenant-qualified Logging & Admin route for bounded cutover-compatibility readback.
- [x] Map the canonical compatibility result, reasons, remap set, and participant results onto a dedicated operator DTO.

###### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: 2. Proof and Docs

- [x] Add focused Logging & Admin controller/service proof for successful readback and tenant-guard failure paths.
- [x] Update Logging & Admin `openapi.yaml` so the published contract includes the new read route and DTO shape.
- [x] Update `08.3` parent/index/progress docs so this operator readback is tracked as landed follow-through rather than remaining implicit.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Acceptance Shape

- Logging & Admin exposes `GET /admission-pointers/version-upgrades/{tenantId}/{sourceGameInstanceId}/compatibility/{targetVersionId}`;
- the route returns the canonical Game Session compatibility proof, including `result`, `reasons`, `checkedParticipants`, optional `remapSetId`, and per-participant results;
- unauthorized callers are rejected before the control-plane read;
- the surface does not create or require a durable prepared-upgrade row just to inspect compatibility.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Completion Notes

- `GameSessionControlPlaneClient` now exposes `validateInstanceCutoverCompatibility(long tenantId, long sourceGameInstanceId, long targetVersionId)` for Logging & Admin.
- `AdmissionPointerController` now serves `GET /admission-pointers/version-upgrades/{tenantId}/{sourceGameInstanceId}/compatibility/{targetVersionId}` and enforces tenant access before delegating.
- `AdmissionPointerServiceImpl` now maps the canonical compatibility verdict, reasons, remap set, checked participants, and participant detail rows onto `InstanceCutoverCompatibilityDto`.
- Logging & Admin `openapi.yaml` now documents the compatibility-read route and DTO shape so the published operator contract matches the landed endpoint.

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Completion Evidence

- Logging & Admin implementation:
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/client/GameSessionControlPlaneClient.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/controller/AdmissionPointerController.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/AdmissionPointerService.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/service/impl/AdmissionPointerServiceImpl.java`
  - `services/logging-admin-service/src/main/java/net/firedevops/firemud/loggingadmin/dto/InstanceCutoverCompatibilityDto.java`
  - `services/logging-admin-service/src/main/resources/openapi.yaml`
- Focused Logging & Admin proof:
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/controller/AdmissionPointerControllerTest.java`
  - `services/logging-admin-service/src/test/java/unit/net/firedevops/firemud/loggingadmin/service/impl/AdmissionPointerServiceImplTest.java`
- Existing Game Session compatibility contract proof reused by this operator surface:
  - `services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/service/impl/GameSessionControlPlaneGrpcServiceTest.java`

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Validation

- `./gradlew :logging-admin-service:test --tests 'net.firedevops.firemud.loggingadmin.controller.AdmissionPointerControllerTest' --tests 'net.firedevops.firemud.loggingadmin.service.impl.AdmissionPointerServiceImplTest'`
- `./gradlew spotlessApply`
- `dev-tools/validation/run-locked-gradle.sh :logging-admin-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

##### source-08-3-1-task-list-operator-cutover-compatibility-readback-vertical-slice-1-87: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end.
- [x] Verify and close follow-ups.
<!-- /migration-source -->
