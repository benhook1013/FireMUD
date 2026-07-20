# Consequential Design Decision Inventory

Status: Complete and independently coverage/fidelity-audited. This artifact is non-normative; the human-led adversarial review is in progress.

This inventory identifies important explicit and implicit product and architecture decisions in canonical FireMUD design. It provides evidence and the authoritative queue for the active adversarial review run manually by the human decision owner. Automated work on this inventory must not accept, reject, supersede, or resolve a decision; accepted target state remains in canonical design and consequential rationale belongs in an ADR.

## Decision Threshold

Inventory a decision when at least one of these applies:

- it establishes authority or ownership across services or product domains;
- it is expensive or disruptive to reverse;
- it materially affects security, tenant isolation, durability, consistency, operations, cost, or player/creator experience;
- it constrains future extensibility or the soft-configured game model;
- it has a credible competing target state;
- current design asserts it without sufficient rationale or explicit human consultation; or
- different canonical sources imply competing choices.

Routine implementation mechanics that do not affect the target-state contract are excluded.

## Status Vocabulary

| Status | Meaning |
| --- | --- |
| `accepted-explicit` | Canonical design and an accepted ADR explicitly establish the choice. This records repository state, not proof of prior human consultation. |
| `accepted-implicit` | Canonical design establishes the choice but no adequate rationale/ADR was found. |
| `proposed` | Design presents a target that still requires explicit acceptance. |
| `deferred` | The choice is intentionally postponed behind an adoption or implementation gate. |
| `conflicting` | Canonical sources imply incompatible target states. |
| `needs-human-review` | Credible alternatives or product consequences require explicit discussion. |

## Review Priority

| Priority | Meaning |
| --- | --- |
| `P0` | Foundational conflict or unsafe ambiguity blocking reliable downstream design. |
| `P1` | High-impact, cross-domain, difficult-to-reverse, or likely under-consulted decision. |
| `P2` | Material bounded decision that should be challenged during its domain review. |
| `P3` | Low-risk rationale/documentation completion. |

## Coverage Summary

The inventory is split into this control ledger and exhaustive source-scoped ledgers:

- [Cross-cutting architecture decisions](./decision-inventory-cross-cutting.md) contains 68 decisions from the ADR set and 22 high-authority system documents.
- [Microservice decisions](./decision-inventory-microservices.md) contains 23 service-only decisions and stronger evidence for 40 cross-cutting decisions from all 76 microservice architecture files.
- [Specialized runtime decisions](./decision-inventory-specialized-runtime.md) contains 54 decisions and stronger evidence for 20 cross-cutting decisions from 39 Redis, scripting, tick, identity, token, migration, shared-library, spatial, authorization, and tracing documents.
- [Product and operations decisions](./decision-inventory-product-operations.md) contains 38 decisions and stronger evidence for 11 existing keys from the remaining 35 product, frontend, authoring, protocol, infrastructure, deployment, recovery, observability, and generated-settings sources.

The source-scoped ledgers contain 183 unique authoritative decision keys with no duplicate keys across ledgers. The nine ADR-backed aliases retained below are navigation entries and do not add to that count. Collectively, the inventories reference all 79 leaf capabilities in the taxonomy.

| Capability | Sources reviewed | Decisions inventoried | Human-review candidates | Coverage state |
| --- | ---: | ---: | ---: | --- |
| Existing ADR set | 15 records plus linked canonical sources | 9 current aliases within the 68 cross-cutting decisions | Prioritized in the source ledgers | Complete initial mapping |
| Cross-cutting system architecture | 22 canonical sources plus ADRs | 68 | Prioritized in the source ledger | Complete and independently audited |
| Microservice architecture | 76 sources | 23 new; stronger evidence for 40 existing keys | Prioritized in the source ledger | Complete and independently audited |
| Specialized runtime architecture | 39 sources | 54 new; stronger evidence for 20 existing keys | Prioritized in the source ledger | Complete and independently audited |
| Product and operations architecture | 35 sources | 38 new; stronger evidence for 11 existing keys | Prioritized in the source ledger | Complete and independently audited |
| **Total unique decision keys** | **All 188 architecture artifacts classified; 172 decision-scan sources plus 16 decision-registry artifacts** | **183** | **Prioritized by source ledger** | **Complete and independently audited** |

## Decision Ledger

| Decision key | Capability | Decision question | Current explicit or implied choice | Status | Priority | Source and ADR | Strongest credible alternative | Review disposition |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `AS-INGRESS-IDEMPOTENCY` | `AS-1` | What identity makes script-event ingress idempotent across retries and failover? | A trigger identity including tenant, region, entity, script, event type, patch version, event ID, and scheduler fence/due point where applicable | `accepted-explicit` | `P1` | [ADR 0001](../../architecture/decisions/adr-0001-scripting-event-ingress-idempotency-identity.md) | Event ID alone, or an infrastructure-generated opaque dedupe key | Accepted record exists; human-led adversarial review pending |
| `AS-HANDOFF-SUCCESS` | `AS-1` | When may automation report a trigger as successful? | Only after resulting commands are accepted into the tick system; admitted work cannot rely solely on best-effort Redis staging | `accepted-explicit` | `P1` | [ADR 0002](../../architecture/decisions/adr-0002-automation-handoff-reliability-and-success-semantics.md) | Define success as DSL evaluation and treat downstream delivery as best effort | Accepted record exists; human-led adversarial review pending |
| `AS-RELOAD-BACKPRESSURE` | `AS-1` | How should event ingress behave while scripts reload? | Explicit application-level backpressure, bounded same-ID retry for selected external events, and no general timer backfill | `accepted-explicit` | `P2` | [ADR 0003](../../architecture/decisions/adr-0003-reload-backpressure-and-retry-contract.md) | Queue all events durably through reload, or drop every event uniformly | Accepted record exists; human-led adversarial review pending |
| `AA-WORLD-SELECTOR-IDENTITY` | `AA-3` | How do players select worlds while internal services preserve authoritative tenant identity? | Lobby-only stable tenant slugs and menu indices resolve server-side to tenant IDs; all non-lobby internals use tenant IDs | `accepted-explicit` | `P1` | [ADR 0005](../../architecture/decisions/adr-0005-tenant-identifiers-in-gameplay-protocol.md) | Expose raw tenant IDs, use mutable names, or accept slugs throughout internal contracts | Accepted record exists; slug ownership and lifecycle need human-led adversarial review |
| `PO-EDGE-SHARDING` | `PO-2` | Does the edge own gameplay shard routing or expose a client-visible shard-handoff signal? | No; Gateway routes to a stable Game Session surface and close outcomes use the unified failure taxonomy | `accepted-explicit` | `P1` | [ADR 0007](../../architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md) | Client-carried routing keys with explicit edge shard selection and reroute close semantics | Accepted record exists; human-led adversarial review pending |
| `GR-GAMEPLAY-CLUSTER-SCOPE` | `GR-1` | What deployment scope does gameplay execution support before a complete cross-cluster contract exists? | One Kubernetes cluster per deployment with in-cluster lease rebalancing; multi-cluster gameplay requires a dedicated design package | `accepted-explicit` | `P1` | [ADR 0008](../../architecture/decisions/adr-0008-multi-cluster-gameplay-sharding-scope.md) | Design cross-cluster routing, trust, data, and failover as current target state now | Accepted adoption gate exists; scale assumptions need review against product requirements |
| `SF-COORDINATION-REDIS-OWNERSHIP` | `SF-2` | Who may own and mutate correctness-sensitive Redis coordination keyspaces? | Explicit narrow owners; non-owners participate only through documented owner-managed contracts and helpers | `accepted-explicit` | `P1` | [ADR 0009](../../architecture/decisions/adr-0009-coordination-redis-ownership-boundary.md) | Shared keyspace ownership with schema conventions and distributed review | Accepted record exists; cross-owner bridge completeness needs review |
| `SF-TCP-PROXY-IDENTITY` | `SF-1` | What production identity allows Gateway to trust TCP Proxy headers? | Exact SPIFFE-style URI SAN under platform PKI; DNS is transitional and fingerprint pinning is break-glass only | `accepted-explicit` | `P2` | [ADR 0010](../../architecture/decisions/adr-0010-tcp-proxy-identity-canonicalization.md) | DNS SAN as steady state, workload identity infrastructure, or certificate fingerprint pinning | Accepted record exists; deployment feasibility and fallback controls need review |
| `GR-SESSION-FRONTEND-EXECUTION` | `GR-1` | How are socket ownership and region execution ownership separated inside Game Session? | A session front-end owns the socket while the fenced lease owner executes region-scoped mutations over ordered internal forwarding | `accepted-explicit` | `P1` | [ADR 0011](../../architecture/decisions/adr-0011-gameplay-session-front-end-and-region-execution.md) | Co-locate socket and execution through affinity/reconnect, or use a dedicated session router | Accepted record exists; forwarding complexity and failure semantics need human-led adversarial review |

## Conflicts And Missing Decisions

The detailed ledgers preserve all conflicts, target/current gaps, weak rationale, and consultation questions. The highest-impact reconciliation items are:

| Decision key | Conflict or missing decision |
| --- | --- |
| `AUTO-01` | Resolved baseline: ADR 0001 now delegates the endpoint-specific identity matrix to the canonical scripting contract table and includes runtime scope plus dry-run separation. |
| `SET-01` | Resolved target: values follow defaults, preset, bootstrap, supported runtime-default, tenant, and game-instance precedence while hard bounds and operator caps apply separately as constraints. The current effective-settings implementation does not yet resolve the complete accepted model. |
| `CONTENT-05` | Resolved target: first-party authoring uses typed Game Design/domain APIs; whole-game packages, round-trip JSON, filesystem projects, Git synchronization, and a promised portable snapshot remain outside the target until a concrete migration, portability, offline-tooling, or whole-game-distribution need is accepted. |
| `SESSION-04` | Resolved target: ordinary non-edge failures use bounded invisible recovery when the edge socket, healthy replacement capacity, and shared authority remain available; the ordinary target is 10 seconds and the hard cutoff is 30 seconds before `1013/backend_unavailable`. Complete real-Game-Session continuity proof remains implementation debt. |
| `SESSION-08` | Resolved target: healthy uninterrupted play is independent of internal JWT lifetime; immutable continuity expiry limits reuse of an old binding after transport loss, disconnected resume uses the stricter continuity and configured windows, and transcript retention or Redis presence never grants authority. Current `PLAY` admission does not yet enforce these deadlines. |
| `SEC-02` | Resolved target: planned Account JWT rotation prepublishes and converges the new public key before signer promotion, retains old verification through token expiry, then prunes; compromise/restore uses an environment-wide hard cutover. Current shared-HMAC issuance and validation fail the accepted player-facing readiness gate. |
| `OPS-04` | Resolved target: routine backups are online environment-wide PostgreSQL snapshots without gameplay pause; player-facing rewind uses only environment-wide `cold_start_restore` with empty Redis, safe participant dispositions, quarantine, and controlled reopen. Current implementation and proof do not satisfy the accepted gates. |
| `CMD-STATUS-01` | Resolved target: evolve `GetGameplayCommandStatus` in place as the single authoritative durable API, with stable pre-retry identity and separate acknowledgement, ingress, execution-outcome, and gameplay-result dimensions. Current fields, persistence, recovery, and proof remain incomplete. |
| `TRACE-01` | Resolved target: metrics and structured logs are the dependable baseline; named workflow tracing, service sampling, and tenant/region sampling are progressively advertised only after end-to-end environment proof. Current tracing remains a narrower best-effort surface. |
| `EDGE-06` / `MS-GW-DYNAMIC-ROUTES` | Resolved target: the version-controlled release catalog is the sole player-facing route authority; bounded ephemeral mutation is dev/test-only, and any production runtime control plane requires a separate future decision. Current endpoint gating, isolation, validation, and proof remain incomplete. |

These are inventory findings, not decisions made by this workstream. They remain inputs to the later human-led adversarial review and explicit human resolution.

## Adversarial Review Queue

This is the authoritative progress surface for the human-led adversarial review. Review decisions in the order below unless the implementation orchestrator records a priority override for a concrete design blocker. After an override is resolved, return to the first unchecked key in the normal queue.

The review facilitator must preserve the current choice's strongest argument, construct the strongest credible opposing case, and wait for the human decision owner to choose `accepted`, `revised`, `deferred`, `withdrawn`, or `superseded`. An unchecked item is `unreviewed`; an active item remains unchecked and gains an `in-review` note. A completed item is checked and records the disposition, review date, and a link to any resulting ADR or canonical design change. Closely coupled keys may be discussed together, but every key receives its own outcome.

### Progress Summary

| Packet | Scope | Reviewed | Total | State |
| --- | --- | ---: | ---: | --- |
| 1 | Known conflicts and drift | 9 | 9 | `completed` |
| 2 | Identity, authority, and security | 28 | 32 | `in-progress` |
| 3 | Execution correctness and durability | 43 | 43 | `completed` |
| 4 | Publishing, settings, and authored behavior | 36 | 36 | `completed` |
| 5 | Gameplay and player experience | 21 | 21 | `completed` |
| 6 | Operations and delivery | 1 | 25 | `in-progress` |
| 7 | Existing ADR-backed and lower-risk remainder | 0 | 17 | `not-started` |
| **Total** | | **138** | **183** | `in-progress` |

### Priority Overrides

No implementation-blocking override is active. Record an override here with the decision keys, blocked capability, blocking question, and requesting branch or PR. An override changes only the next work item; it does not remove or duplicate keys in the normal queue.

### Packet 1: Known Conflicts And Drift

#### Packet 1 P0

- [x] `SET-01` — `revised` on 2026-07-18; [ADR 0012](../../architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md); [canonical settings model](../../architecture/system-architecture-settings-model.md)
- [x] `SESSION-04` — `revised` on 2026-07-18; [ADR 0013](../../architecture/decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md); [canonical reconnection contract](../../architecture/system-architecture-reconnection.md)
- [x] `SEC-02` — `revised` on 2026-07-18; [ADR 0014](../../architecture/decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md); [canonical JWT rotation workflow](../../architecture/system-architecture-security.md#jwt-key--jwks-rotation-workflow)
- [x] `OPS-04` — `revised` on 2026-07-18; [ADR 0015](../../architecture/decisions/adr-0015-online-backup-and-environment-wide-cold-start-recovery.md); [canonical backup and recovery contract](../../architecture/system-architecture-backup-recovery.md)
- [x] `CMD-STATUS-01` — `revised` on 2026-07-18; [ADR 0016](../../architecture/decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md); [canonical command lifecycle](../../architecture/system-architecture-tick-execution-flows.md#command-ingress-acknowledgement-contract-required)
- [x] `TRACE-01` — `revised` on 2026-07-18; [ADR 0017](../../architecture/decisions/adr-0017-capability-gated-operational-tracing.md); [capability-gated tracing contract](../../architecture/system-architecture-tracing.md#implementation-notes)
- [x] `EDGE-06` — `revised` on 2026-07-18; [ADR 0018](../../architecture/decisions/adr-0018-declarative-production-gateway-routes.md); [canonical route authority](../../architecture/system-architecture-gateway.md#dynamic-route-override-lifecycle)
- [x] `MS-GW-DYNAMIC-ROUTES` — `revised` on 2026-07-18; [ADR 0018](../../architecture/decisions/adr-0018-declarative-production-gateway-routes.md); [Gateway API boundary](../../architecture/microservices/spring-cloud-gateway/api-contracts.md#dynamic-route-management)

#### Packet 1 P1

- [x] `SESSION-08` — `revised` on 2026-07-18; [ADR 0019](../../architecture/decisions/adr-0019-separate-active-session-resume-and-transcript-lifetimes.md); [canonical session lifetime contract](../../architecture/system-architecture-session-behavior.md#session-types-and-lifetimes)

### Packet 2: Identity, Authority, And Security

#### Packet 2 P0

- [x] `ID-01` — `revised` on 2026-07-18; [ADR 0020](../../architecture/decisions/adr-0020-scoped-domain-and-operational-identifiers.md); [canonical identifier contract](../../architecture/system-architecture-identifier-glossary.md)
- [x] `AUTH-02` — `revised` on 2026-07-18; [ADR 0021](../../architecture/decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md)
- [x] `AUTH-03` — `accepted` on 2026-07-18; [ADR 0022](../../architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md)
- [x] `AUTH-04` — `revised` on 2026-07-18; [ADR 0023](../../architecture/decisions/adr-0023-central-route-authorization-governance.md)
- [x] `AUTH-05` — `revised` on 2026-07-19; [ADR 0024](../../architecture/decisions/adr-0024-trusted-gameplay-workload-delegation.md)
- [x] `AUTH-06` — `revised` on 2026-07-19; [ADR 0025](../../architecture/decisions/adr-0025-explicit-open-enrollment-membership.md)
- [x] `AUTH-07` — `revised` on 2026-07-19; [ADR 0026](../../architecture/decisions/adr-0026-global-roles-do-not-grant-gameplay-authority.md)
- [x] `TENANT-01` — `revised` on 2026-07-19; [ADR 0027](../../architecture/decisions/adr-0027-single-realm-admission-target.md)
- [x] `ADMIT-01` — `revised` on 2026-07-19; [ADR 0028](../../architecture/decisions/adr-0028-differentiated-entitlement-freshness.md)
- [x] `EDGE-04` — `revised` on 2026-07-19; [ADR 0029](../../architecture/decisions/adr-0029-single-use-gameplay-connect-token-carriage.md)
- [x] `SESSION-07` — `revised` on 2026-07-19; [ADR 0030](../../architecture/decisions/adr-0030-risk-based-active-session-revocation.md)
- [x] `SESSION-09` — `revised` on 2026-07-19; [ADR 0031](../../architecture/decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md)
- [x] `SEC-01` — `revised` on 2026-07-19; [ADR 0032](../../architecture/decisions/adr-0032-kubernetes-native-secret-delivery-without-mandatory-vault.md)
- [x] `SEC-03` — `revised` on 2026-07-19; [ADR 0033](../../architecture/decisions/adr-0033-public-player-facing-telnet-requires-tls.md)
- [x] `SEC-05` — `revised` on 2026-07-19; [ADR 0034](../../architecture/decisions/adr-0034-layered-abuse-controls-without-attacker-triggered-account-locks.md)
- [x] `JWT-01` — `revised` on 2026-07-19; [ADR 0035](../../architecture/decisions/adr-0035-single-record-issued-token-registry.md)
- [x] `JWT-02` — `revised` on 2026-07-19; [ADR 0036](../../architecture/decisions/adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md)
- [x] `JWT-03` — `revised` on 2026-07-19; [ADR 0037](../../architecture/decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md)
- [x] `JWT-04` — `revised` on 2026-07-19; [ADR 0038](../../architecture/decisions/adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md)
- [x] `REDIS-06` — `revised` on 2026-07-19; [ADR 0039](../../architecture/decisions/adr-0039-bounded-redis-operator-maintenance-surface.md)
- [x] `MS-AA-CONTROL-LOGIN-SCOPE` — `revised` on 2026-07-19; [ADR 0040](../../architecture/decisions/adr-0040-account-global-control-login-and-explicit-tenant-selection.md)
- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-19 by [ADR 0022](../../architecture/decisions/adr-0022-account-authority-and-gameplay-session-ownership.md) and [ADRs 0035–0038](../../architecture/decisions/README.md)

#### Packet 2 P1

- [x] `TENANT-02` — `revised` on 2026-07-19; [ADR 0041](../../architecture/decisions/adr-0041-shared-tenant-infrastructure-with-full-environment-isolation-gate.md)
- [x] `MS-AA-GLOBAL-TENANT-BOUNDARY` — `revised` on 2026-07-19; [ADR 0042](../../architecture/decisions/adr-0042-global-account-and-tenant-scoped-game-relationships.md)
- [x] `MS-AA-LIFECYCLE-ERASURE` — `revised` on 2026-07-19; [ADR 0043](../../architecture/decisions/adr-0043-global-account-lifecycle-and-bounded-erasure-workflow.md)
- [x] `MS-AA-PAYMENT-INSTRUMENT` — `revised` on 2026-07-19; [ADR 0044](../../architecture/decisions/adr-0044-account-owned-payment-instruments-with-explicit-subscription-binding.md)
- [x] `MS-AA-LOGIN-FACTORS` — `revised` on 2026-07-19; [ADR 0045](../../architecture/decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md)
- [x] `MS-SOCIAL-PRESENCE-PRIVACY` — `revised` on 2026-07-19; [ADR 0046](../../architecture/decisions/adr-0046-bounded-friend-presence-with-private-by-failure-redaction.md)
- [x] `SEC-04` — `revised` on 2026-07-19; [ADR 0047](../../architecture/decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md)
- [x] `ADMIN-01` — `revised` on 2026-07-19; [ADR 0048](../../architecture/decisions/adr-0048-durable-idempotent-operator-write-execution.md)
- [x] `ACCOUNT-01` — `revised` on 2026-07-19; [ADR 0049](../../architecture/decisions/adr-0049-optional-provider-specific-external-identity-linking.md)
- [x] `DATA-01` — `revised` on 2026-07-19; [ADR 0050](../../architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md)

### Packet 3: Execution Correctness And Durability

#### Packet 3 P0

- [x] `TICK-01` — `revised` on 2026-07-19; [ADR 0051](../../architecture/decisions/adr-0051-separate-actor-action-and-effect-lanes.md)
- [x] `TICK-02` — `revised` on 2026-07-19; [ADR 0052](../../architecture/decisions/adr-0052-redis-liveness-lease-with-durable-executor-fence.md)
- [x] `TICK-03` — `revised` on 2026-07-19; [ADR 0053](../../architecture/decisions/adr-0053-command-atomicity-by-invariant-class.md)
- [x] `TICK-04` — `revised` on 2026-07-19; [ADR 0054](../../architecture/decisions/adr-0054-split-spatial-authority-with-causal-read-composition.md)
- [x] `TICK-06` — `revised` on 2026-07-19; [ADR 0055](../../architecture/decisions/adr-0055-durable-cross-region-effects-with-static-live-topology.md)
- [x] `HOTPATH-01` — `revised` on 2026-07-19; [ADR 0056](../../architecture/decisions/adr-0056-one-hot-path-fan-out-owner.md)
- [x] `RECON-01` — `revised` on 2026-07-19; [ADR 0057](../../architecture/decisions/adr-0057-game-session-owned-reconciliation-with-isolated-workers.md)
- [x] `REDIS-01` — `revised` on 2026-07-19; [ADR 0058](../../architecture/decisions/adr-0058-class-specific-redis-loss-outcomes.md)
- [x] `ID-02` — `revised` on 2026-07-19; [ADR 0059](../../architecture/decisions/adr-0059-causal-floor-cross-service-presentation-reads.md)
- [x] `MS-GR-AMBIENT-STATE-AUTHORITY` — `revised` on 2026-07-19; [ADR 0060](../../architecture/decisions/adr-0060-world-owned-ambient-facts-and-logic-owned-consequences.md)
- [x] `SPATIAL-01` — `revised` on 2026-07-19; [ADR 0061](../../architecture/decisions/adr-0061-single-owner-spatial-mutations-across-split-authority.md)
- [x] `SESSION-06` — `revised` on 2026-07-19; [ADR 0062](../../architecture/decisions/adr-0062-layered-gameplay-command-delivery-semantics.md)
- [x] `SCRIPT-01` — `revised` on 2026-07-19; [ADR 0063](../../architecture/decisions/adr-0063-durable-per-dispatch-script-handoff.md)
- [x] `SCRIPT-04` — `revised` on 2026-07-19; [ADR 0064](../../architecture/decisions/adr-0064-stage-qualified-script-outcomes.md)
- [x] `TICK-09` — `revised` on 2026-07-19; [ADR 0065](../../architecture/decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md)
- [x] `TICK-13` — `revised` on 2026-07-19; [ADR 0066](../../architecture/decisions/adr-0066-durable-asynchronous-cross-region-result-arbitration.md)
- [x] `TICK-14` — `revised` on 2026-07-19; [ADR 0067](../../architecture/decisions/adr-0067-abandon-old-epoch-work-and-reschedule-with-new-lineage.md)
- [x] `TICK-15` — `revised` on 2026-07-19; [ADR 0068](../../architecture/decisions/adr-0068-evidence-derived-bounded-tick-ledger-recovery.md)
- [x] `TICK-16` — `revised` on 2026-07-19; [ADR 0069](../../architecture/decisions/adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md)
- [x] `TICK-17` — `revised` on 2026-07-19; [ADR 0070](../../architecture/decisions/adr-0070-bounded-within-tick-visibility-by-semantic-phase.md)
- [x] `TICK-19` — `revised` on 2026-07-19; [ADR 0071](../../architecture/decisions/adr-0071-durable-tick-commit-before-fenced-coordination-cleanup.md)

#### Packet 3 P1

- [x] `TICK-05` — `accepted` on 2026-07-19; [canonical workflow substrate boundary](../../architecture/system-architecture-transactions.md#saga-vs-temporal-boundary)
- [x] `TICK-07` — `revised` on 2026-07-19; [ADR 0072](../../architecture/decisions/adr-0072-class-specific-timer-durability-and-recovery.md)
- [x] `TICK-08` — `revised` on 2026-07-19; [ADR 0073](../../architecture/decisions/adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md)
- [x] `TICK-10` — `revised` on 2026-07-19; [ADR 0074](../../architecture/decisions/adr-0074-one-entity-lock-per-redis-script.md)
- [x] `TICK-11` — `revised` on 2026-07-20; [ADR 0075](../../architecture/decisions/adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md)
- [x] `TICK-12` — `revised` on 2026-07-20; [ADR 0076](../../architecture/decisions/adr-0076-failure-class-specific-durable-tick-retries.md)
- [x] `TICK-18` — `revised` on 2026-07-20; [ADR 0077](../../architecture/decisions/adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md)
- [x] `ID-03` — `revised` on 2026-07-20; [ADR 0078](../../architecture/decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md)
- [x] `DB-01` — `accepted` on 2026-07-20; [ADR 0079](../../architecture/decisions/adr-0079-jooq-and-flyway-as-the-single-sql-persistence-stack.md)
- [x] `DB-02` — `accepted` on 2026-07-20; [ADR 0080](../../architecture/decisions/adr-0080-service-owned-schemas-with-adopter-local-shared-migrations.md)
- [x] `DB-03` — `revised` on 2026-07-20; [ADR 0081](../../architecture/decisions/adr-0081-objective-compatibility-gates-for-database-evolution.md)
- [x] `DB-04` — `revised` on 2026-07-20; [ADR 0082](../../architecture/decisions/adr-0082-semantic-boundary-for-cross-service-identifier-migration.md)
- [x] `REDIS-03` — `revised` on 2026-07-20; [ADR 0083](../../architecture/decisions/adr-0083-no-general-event-broker-until-measured-adoption-gates.md)
- [x] `REDIS-04` — `revised` on 2026-07-20; [ADR 0084](../../architecture/decisions/adr-0084-evidence-scoped-redis-lua-compatibility.md)
- [x] `REDIS-05` — `revised` on 2026-07-20; [ADR 0085](../../architecture/decisions/adr-0085-evidence-gated-coordination-replay-and-fenced-reset.md)
- [x] `CACHE-01` — `revised` on 2026-07-20; [ADR 0086](../../architecture/decisions/adr-0086-owner-validated-class-a-caches-and-presentation-only-class-b.md)
- [x] `CACHE-02` — `revised` on 2026-07-20; [ADR 0087](../../architecture/decisions/adr-0087-isolated-subject-rate-limits-with-explicit-loss-semantics.md)
- [x] `SCRIPT-02` — `revised` on 2026-07-20; [ADR 0088](../../architecture/decisions/adr-0088-static-and-incremental-script-output-bounds.md)
- [x] `SCRIPT-03` — `revised` on 2026-07-20; [ADR 0089](../../architecture/decisions/adr-0089-durable-script-usage-charges-and-fenced-capacity-leases.md)
- [x] `SCRIPT-12` — `revised` on 2026-07-20; [ADR 0090](../../architecture/decisions/adr-0090-recorded-script-input-manifests-for-reproducible-evaluation.md)
- [x] `TIMER-01` — `revised` on 2026-07-20; [ADR 0091](../../architecture/decisions/adr-0091-class-specific-script-timer-clocks-and-recovery.md)
- [x] `GRPC-01` — `revised` on 2026-07-20; [ADR 0092](../../architecture/decisions/adr-0092-grpc-status-and-typed-domain-outcome-boundary.md)

### Packet 4: Publishing, Settings, And Authored Behavior

#### Packet 4 P0

- [x] `CONTENT-01` — `revised` on 2026-07-20; [ADR 0093](../../architecture/decisions/adr-0093-game-design-coordinated-digest-attested-content-publication.md)
- [x] `CONTENT-02` — `revised` on 2026-07-20; [ADR 0094](../../architecture/decisions/adr-0094-explicit-cohesive-runtime-release-tuples.md)
- [x] `ASSET-01` — `revised` on 2026-07-20; [ADR 0095](../../architecture/decisions/adr-0095-content-addressed-published-assets-with-cas-lifecycle-authority.md)
- [x] `ASSET-02` — `revised` on 2026-07-20; [ADR 0096](../../architecture/decisions/adr-0096-attested-publication-gate-and-quarantined-failed-assets.md)
- [x] `PROMO-01` — `revised` on 2026-07-20; [ADR 0097](../../architecture/decisions/adr-0097-git-and-ci-validated-single-operator-promotion-evidence.md)
- [x] `PROC-02` — `revised` on 2026-07-20; [ADR 0098](../../architecture/decisions/adr-0098-request-bounded-generation-replay-and-explicit-regeneration.md)
- [x] `PROC-04` — `revised` on 2026-07-20; [ADR 0099](../../architecture/decisions/adr-0099-bounded-atomic-generation-with-staging-for-large-outputs.md)
- [x] `MS-AS-PATCH-READINESS-PIN` — `revised` on 2026-07-20; [ADR 0100](../../architecture/decisions/adr-0100-single-authority-script-pins-with-exact-version-execution.md)
- [x] `MS-GR-REPLACEMENT-STATE` — `revised` on 2026-07-20; [ADR 0101](../../architecture/decisions/adr-0101-stable-playable-state-namespaces-for-runtime-replacement.md)
- [x] `MS-GR-WORLD-LIFECYCLE` — `revised` on 2026-07-20; [ADR 0102](../../architecture/decisions/adr-0102-database-authoritative-temporal-coordinated-world-lifecycle.md)
- [x] `SCRIPT-06` — `revised` on 2026-07-20; [ADR 0103](../../architecture/decisions/adr-0103-epoch-fenced-script-rollback-without-routine-gameplay-pause.md)
- [x] `SCRIPT-07` — `revised` on 2026-07-20; [ADR 0104](../../architecture/decisions/adr-0104-stage-aware-script-dead-letter-recovery.md)
- [x] `SCRIPT-08` — `revised` on 2026-07-20; [ADR 0105](../../architecture/decisions/adr-0105-no-degraded-script-admission-without-authoritative-pin.md)
- [x] `SCRIPT-16` — `revised` on 2026-07-20; [ADR 0106](../../architecture/decisions/adr-0106-game-session-owned-script-rollout-history.md)
- [x] `TIMER-02` — `revised` on 2026-07-20; [ADR 0107](../../architecture/decisions/adr-0107-explicit-opt-in-schedule-continuity-across-script-transitions.md)

#### Packet 4 P1

- [x] `CONTENT-03` — `revised` on 2026-07-20; [ADR 0108](../../architecture/decisions/adr-0108-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md)
- [x] `CONTENT-04` — `revised` on 2026-07-20; [ADR 0109](../../architecture/decisions/adr-0109-materialized-starter-profiles-with-conservative-draft-upgrades.md)
- [x] `CONTENT-05` — `deferred` on 2026-07-20; [ADR 0110](../../architecture/decisions/adr-0110-defer-whole-game-portability-and-external-authoring-formats.md)
- [x] `CMD-02` — `accepted` on 2026-07-20; [ADR 0111](../../architecture/decisions/adr-0111-typed-bounded-gameplay-effect-extension.md)
- [x] `SET-02` — `revised` on 2026-07-20; [ADR 0112](../../architecture/decisions/adr-0112-bounded-pull-settings-distribution-with-freshness-classes.md)
- [x] `LLM-01` — `revised` on 2026-07-20; [ADR 0116](../../architecture/decisions/adr-0116-untrusted-models-and-scoped-authoring-tools.md)
- [x] `PROC-01` — `revised` on 2026-07-20; [ADR 0113](../../architecture/decisions/adr-0113-separate-generation-ingress-with-one-world-owned-engine.md)
- [x] `PROC-03` — `revised` on 2026-07-20; [ADR 0114](../../architecture/decisions/adr-0114-explicit-destructive-regeneration-with-previewed-scope.md)
- [x] `PROC-05` — `revised` on 2026-07-20; [ADR 0117](../../architecture/decisions/adr-0117-first-class-sparse-and-full-grid-world-topologies.md)
- [x] `EQUIP-01` — `revised` on 2026-07-20; [ADR 0115](../../architecture/decisions/adr-0115-game-authored-equipment-layouts-with-fail-closed-publication.md)
- [x] `MS-AS-PLUGIN-TRUST` — `accepted` on 2026-07-20; [ADR 0108](../../architecture/decisions/adr-0108-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md)
- [x] `MS-AS-DRY-RUN-ISOLATION` — `revised` on 2026-07-20; [ADR 0118](../../architecture/decisions/adr-0118-command-plan-preview-dry-run-isolation.md)
- [x] `MS-AR-DRAFT-CONCURRENCY` — `revised` on 2026-07-20; [ADR 0119](../../architecture/decisions/adr-0119-durable-fenced-multi-owner-draft-commits.md)
- [x] `MS-GR-EQUIPMENT-BODY-LAYOUT` — `superseded` on 2026-07-20 by `EQUIP-01`; [ADR 0115](../../architecture/decisions/adr-0115-game-authored-equipment-layouts-with-fail-closed-publication.md)
- [x] `SCRIPT-05` — `revised` on 2026-07-20; [ADR 0120](../../architecture/decisions/adr-0120-manifest-complete-onload-readiness-without-durable-game-initialization.md)
- [x] `SCRIPT-09` — `superseded` on 2026-07-20 by `MS-AS-DRY-RUN-ISOLATION`; [ADR 0118](../../architecture/decisions/adr-0118-command-plan-preview-dry-run-isolation.md)
- [x] `SCRIPT-10` — `revised` on 2026-07-20; [ADR 0121](../../architecture/decisions/adr-0121-routine-component-migration-and-explicit-emergency-revocation.md)
- [x] `SCRIPT-11` — `accepted` on 2026-07-20; [ADR 0122](../../architecture/decisions/adr-0122-producer-owned-event-schemas-with-one-materialized-catalogue.md)
- [x] `SCRIPT-13` — `revised` on 2026-07-20; [ADR 0123](../../architecture/decisions/adr-0123-preselected-exclusive-handlers-and-durable-fanout-ordering.md)
- [x] `PLUGIN-01` — `revised` on 2026-07-20; [ADR 0124](../../architecture/decisions/adr-0124-epoch-fenced-per-instance-plugin-activation.md)
- [x] `CP-01` — `revised` on 2026-07-20; [ADR 0125](../../architecture/decisions/adr-0125-owner-read-first-control-plane-notifications.md)

### Packet 5: Gameplay And Player Experience

#### Packet 5 P0

- [x] `TENANT-03` — `revised` on 2026-07-20; [ADR 0126](../../architecture/decisions/adr-0126-isolated-playtest-state-modes-and-reset.md)
- [x] `EDGE-05` — `revised` on 2026-07-20; [ADR 0127](../../architecture/decisions/adr-0127-lifecycle-distinct-gameplay-close-taxonomy.md)
- [x] `SESSION-02` — `revised` on 2026-07-20; [ADR 0128](../../architecture/decisions/adr-0128-namespace-scoped-single-character-controller.md)
- [x] `SESSION-03` — `revised` on 2026-07-20; [ADR 0129](../../architecture/decisions/adr-0129-fresh-edge-reconnect-without-client-input-replay.md)
- [x] `CMD-04` — `revised` on 2026-07-20; [ADR 0130](../../architecture/decisions/adr-0130-bounded-durable-semantic-reconnect-context.md)

#### Packet 5 P1

- [x] `CMD-03` — `revised` on 2026-07-20; [ADR 0131](../../architecture/decisions/adr-0131-compact-versioned-player-output-and-late-rendering.md)
- [x] `CMD-05` — `revised` on 2026-07-20; [ADR 0132](../../architecture/decisions/adr-0132-future-compatible-localization-boundary.md)
- [x] `MOD-01` — `revised` on 2026-07-20; [ADR 0133](../../architecture/decisions/adr-0133-owner-local-moderation-enforcement.md)
- [x] `MS-GR-COMMUNICATION-ORCHESTRATION` — `revised` on 2026-07-20; [ADR 0134](../../architecture/decisions/adr-0134-explicit-communication-classes-and-owner-delivery.md)
- [x] `MS-SOCIAL-RELATIONSHIP-AUTHORITY` — `revised` on 2026-07-20; [ADR 0135](../../architecture/decisions/adr-0135-social-relationship-authority-and-entity-owned-value.md)
- [x] `MS-SOCIAL-HISTORY-DURABILITY` — `revised` on 2026-07-20; [ADR 0136](../../architecture/decisions/adr-0136-communication-type-specific-history-and-retention.md)
- [x] `MS-SOCIAL-OBSERVER-SHOUT-POLICY` — `revised` on 2026-07-20; [ADR 0137](../../architecture/decisions/adr-0137-closed-observer-views-and-profile-scoped-shout.md)
- [x] `PLAYTEST-01` — `revised` on 2026-07-20; [ADR 0138](../../architecture/decisions/adr-0138-expiring-playtest-grants-with-bounded-active-revocation.md)
- [x] `LIFE-01` — `revised` on 2026-07-20; [ADR 0139](../../architecture/decisions/adr-0139-tenant-owned-runtime-lifecycle-with-audited-break-glass.md)
- [x] `PLAYER-01` — `revised` on 2026-07-20; [ADR 0140](../../architecture/decisions/adr-0140-realm-authored-controllable-actor-entry.md)
- [x] `SAFETY-01` — `revised` on 2026-07-20; [ADR 0141](../../architecture/decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md)
- [x] `COMMERCE-01` — `revised` on 2026-07-20; [ADR 0143](../../architecture/decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md)
- [x] `SOCIAL-01` — `superseded` on 2026-07-20 by [ADR 0134](../../architecture/decisions/adr-0134-explicit-communication-classes-and-owner-delivery.md), [ADR 0136](../../architecture/decisions/adr-0136-communication-type-specific-history-and-retention.md), and [ADR 0137](../../architecture/decisions/adr-0137-closed-observer-views-and-profile-scoped-shout.md)
- [x] `MS-PO-MODERATION-APPEALS` — `revised` on 2026-07-20; [ADR 0142](../../architecture/decisions/adr-0142-bounded-moderation-appeal-cases.md)

#### Packet 5 P2 And P3

- [x] `FRONT-01` — `revised` on 2026-07-20; [ADR 0144](../../architecture/decisions/adr-0144-stateless-first-party-frontend-application-boundary.md)
- [x] `MCP-01` — `revised` on 2026-07-20; [ADR 0145](../../architecture/decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md)

### Packet 6: Operations And Delivery

#### Packet 6 P0

- [x] `COMPLIANCE-01` — `revised` on 2026-07-20; [ADR 0146](../../architecture/decisions/adr-0146-event-scoped-automated-tier-a-credential-compliance.md)
- [ ] `PREFLIGHT-02`
- [ ] `PREFLIGHT-01`
- [ ] `OPS-03`
- [ ] `RECOVERY-01`
- [ ] `RECOVERY-02`
- [ ] `OPS-02`
- [ ] `HEALTH-01`
- [ ] `OBS-04`
- [ ] `OBS-05`

#### Packet 6 P1

- [ ] `OBS-01`
- [ ] `OBS-02`
- [ ] `OBS-03`
- [ ] `OBS-06`
- [ ] `CAPACITY-01`
- [ ] `CAPACITY-02`
- [ ] `TEST-01`
- [ ] `TEST-02`
- [ ] `TEST-03`
- [ ] `MS-OPS-AVAILABILITY-PARTITION`
- [ ] `MS-PO-OWNER-REMEDIATION`
- [ ] `SCRIPT-14`
- [ ] `SCRIPT-15`
- [ ] `TRACE-02`

#### Packet 6 P2 And P3

- [ ] `TRACE-03`

### Packet 7: Existing ADR-Backed And Lower-Risk Remainder

#### Packet 7 P2 And P3

- [ ] `AUTH-01`
- [ ] `EDGE-01`
- [ ] `EDGE-02`
- [ ] `EDGE-03`
- [ ] `SESSION-01`
- [ ] `SESSION-05`
- [ ] `REDIS-02`
- [ ] `AUTO-01`
- [ ] `AUTO-02`
- [ ] `AUTO-03`
- [ ] `GRPC-02`
- [ ] `CMD-01`
- [ ] `CMD-06`
- [ ] `LIB-01`
- [ ] `OPS-01`
- [ ] `OPS-05`
- [ ] `OPS-06`

### Allocation Notes

- `EDGE-06` and `MS-GW-DYNAMIC-ROUTES` describe the same dynamic-route boundary but remain separate authoritative keys and must be reviewed together.
- `SESSION-08` remained in Packet 1 because the control inventory recorded target/current drift requiring human verification; that review is now complete.
- Publication authority precedes deployment gates, so `CONTENT-01`, `ASSET-01`, `ASSET-02`, and `PROMO-01` remain in Packet 4 rather than Packet 6.
- Version admission and rollback convergence precede execution, so the patch, plugin, and timer-reload decisions remain in Packet 4 rather than Packet 3.
- Account authority, identity scope, and erasure govern their player-facing consequences, so `DATA-01` and `ACCOUNT-01` remain in Packet 2 rather than Packet 5.
