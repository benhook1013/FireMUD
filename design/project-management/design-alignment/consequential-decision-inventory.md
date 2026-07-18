# Consequential Design Decision Inventory

Status: Complete and independently coverage/fidelity-audited. This artifact is non-normative; the human-led adversarial review is in progress.

## Implementation Status

`Complete` applies only to inventory scan coverage and fidelity auditing. Decision implementation, approval, and adversarial-review state remain authoritative in the row statuses and review queue below; proposed, conflicting, and human-review-required decisions remain unresolved.

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
| `accepted-explicit` | Canonical design, an accepted ADR, or both explicitly establish the choice. This records repository state, not proof of prior human consultation. |
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
| Existing ADR set | 19 records plus linked canonical sources | 9 current aliases within the 68 cross-cutting decisions | Prioritized in the source ledgers | Complete initial mapping |
| Cross-cutting system architecture | 22 canonical sources plus ADRs | 68 | Prioritized in the source ledger | Complete and independently audited |
| Microservice architecture | 76 sources | 23 new; stronger evidence for 40 existing keys | Prioritized in the source ledger | Complete and independently audited |
| Specialized runtime architecture | 39 sources | 54 new; stronger evidence for 20 existing keys | Prioritized in the source ledger | Complete and independently audited |
| Product and operations architecture | 35 sources | 38 new; stronger evidence for 11 existing keys | Prioritized in the source ledger | Complete and independently audited |
| **Total unique decision keys** | **All 192 architecture artifacts classified; 172 decision-scan sources plus 20 decision-registry artifacts** | **183** | **Prioritized by source ledger** | **Complete and independently audited** |

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
| `CONTENT-05` | Resolved baseline: first-party authoring uses Game Design-owned revision and domain APIs; bulk JSON import/export remains deferred until it has a validated package contract. |
| `SESSION-04` | Resolved target: ordinary non-edge failures use bounded invisible recovery when the edge socket, healthy replacement capacity, and shared authority remain available; the ordinary target is 10 seconds and the hard cutoff is 30 seconds before `1013/backend_unavailable`. Complete real-Game-Session continuity proof remains implementation debt. |
| `SESSION-08` | Resolved target: healthy uninterrupted play is independent of internal JWT lifetime; immutable continuity expiry limits reuse of an old binding after transport loss, disconnected resume uses the stricter continuity and configured windows, and transcript retention or Redis presence never grants authority. Current `PLAY` admission does not yet enforce these deadlines. |
| `SEC-02` | Resolved target: planned Account JWT rotation prepublishes and converges the new public key before signer promotion, retains old verification through token expiry, then prunes; compromise/restore uses an environment-wide hard cutover. Current shared-HMAC issuance and validation fail the accepted player-facing readiness gate. |
| `OPS-04` | Resolved target: routine backups are online environment-wide PostgreSQL snapshots without gameplay pause; player-facing rewind uses only environment-wide `cold_start_restore` with empty Redis, safe participant dispositions, quarantine, and controlled reopen. The durable recovery controller is the runtime authority for the gated reopen transition; checked-in recovery evidence is a finalized projection. Current implementation and proof do not satisfy the accepted gates. |
| `CMD-STATUS-01` | Resolved target: evolve `GetGameplayCommandStatus` in place as the single authoritative durable API, with stable pre-retry identity and separate acknowledgement, ingress, execution-outcome, and gameplay-result dimensions. Current fields, persistence, recovery, and proof remain incomplete. |
| `TRACE-01` | Resolved target: metrics and structured logs are the dependable baseline; named workflow tracing, service sampling, and tenant/region sampling are progressively advertised only after end-to-end environment proof. Current tracing remains a narrower best-effort surface. |
| `EDGE-06` / `MS-GW-DYNAMIC-ROUTES` | Resolved target: the version-controlled release catalog is the sole player-facing route authority; bounded ephemeral mutation is dev/test-only, and any production runtime control plane requires a separate future decision. Current endpoint gating, isolation, validation, and proof remain incomplete. |

These rows preserve the original conflict inventory and now summarize its human-reviewed resolutions. The linked ADRs and canonical design are authoritative for the accepted target state; implementation gaps remain tracked separately.

## Adversarial Review Queue

This is the authoritative progress surface for the human-led adversarial review. Review decisions in the order below unless the implementation orchestrator records a priority override for a concrete design blocker. After an override is resolved, return to the first unchecked key in the normal queue.

The review facilitator must preserve the current choice's strongest argument, construct the strongest credible opposing case, and wait for the human decision owner to choose `accepted`, `revised`, `deferred`, `withdrawn`, or `superseded`. An unchecked item is `unreviewed`; an active item remains unchecked and gains an `in-review` note. A completed item is checked and records the disposition, review date, and a link to any resulting ADR or canonical design change. Closely coupled keys may be discussed together, but every key receives its own outcome.

### Progress Summary

| Packet | Scope | Reviewed | Total | State |
| --- | --- | ---: | ---: | --- |
| 1 | Known conflicts and drift | 9 | 9 | `completed` |
| 2 | Identity, authority, and security | 1 | 32 | `in-progress` |
| 3 | Execution correctness and durability | 0 | 43 | `not-started` |
| 4 | Publishing, settings, and authored behavior | 0 | 36 | `not-started` |
| 5 | Gameplay and player experience | 0 | 21 | `not-started` |
| 6 | Operations and delivery | 0 | 25 | `not-started` |
| 7 | Existing ADR-backed and lower-risk remainder | 0 | 17 | `not-started` |
| **Total** | | **10** | **183** | `in-progress` |

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
- [ ] `AUTH-02`
- [ ] `AUTH-03`
- [ ] `AUTH-04`
- [ ] `AUTH-05`
- [ ] `AUTH-06`
- [ ] `AUTH-07`
- [ ] `TENANT-01`
- [ ] `ADMIT-01`
- [ ] `EDGE-04`
- [ ] `SESSION-07`
- [ ] `SESSION-09`
- [ ] `SEC-01`
- [ ] `SEC-03`
- [ ] `SEC-05`
- [ ] `JWT-01`
- [ ] `JWT-02`
- [ ] `JWT-03`
- [ ] `JWT-04`
- [ ] `REDIS-06`
- [ ] `MS-AA-CONTROL-LOGIN-SCOPE`
- [ ] `MS-AA-TOKEN-REVOCATION`

#### Packet 2 P1

- [ ] `TENANT-02`
- [ ] `MS-AA-GLOBAL-TENANT-BOUNDARY`
- [ ] `MS-AA-LIFECYCLE-ERASURE`
- [ ] `MS-AA-PAYMENT-INSTRUMENT`
- [ ] `MS-AA-LOGIN-FACTORS`
- [ ] `MS-SOCIAL-PRESENCE-PRIVACY`
- [ ] `SEC-04`
- [ ] `ADMIN-01`
- [ ] `ACCOUNT-01`
- [ ] `DATA-01`

### Packet 3: Execution Correctness And Durability

#### Packet 3 P0

- [ ] `TICK-01`
- [ ] `TICK-02`
- [ ] `TICK-03`
- [ ] `TICK-04`
- [ ] `TICK-06`
- [ ] `HOTPATH-01`
- [ ] `RECON-01`
- [ ] `REDIS-01`
- [ ] `ID-02`
- [ ] `MS-GR-AMBIENT-STATE-AUTHORITY`
- [ ] `SPATIAL-01`
- [ ] `SESSION-06`
- [ ] `SCRIPT-01`
- [ ] `SCRIPT-04`
- [ ] `TICK-09`
- [ ] `TICK-13`
- [ ] `TICK-14`
- [ ] `TICK-15`
- [ ] `TICK-16`
- [ ] `TICK-17`
- [ ] `TICK-19`

#### Packet 3 P1

- [ ] `TICK-05`
- [ ] `TICK-07`
- [ ] `TICK-08`
- [ ] `TICK-10`
- [ ] `TICK-11`
- [ ] `TICK-12`
- [ ] `TICK-18`
- [ ] `ID-03`
- [ ] `DB-01`
- [ ] `DB-02`
- [ ] `DB-03`
- [ ] `DB-04`
- [ ] `REDIS-03`
- [ ] `REDIS-04`
- [ ] `REDIS-05`
- [ ] `CACHE-01`
- [ ] `CACHE-02`
- [ ] `SCRIPT-02`
- [ ] `SCRIPT-03`
- [ ] `SCRIPT-12`
- [ ] `TIMER-01`
- [ ] `GRPC-01`

### Packet 4: Publishing, Settings, And Authored Behavior

#### Packet 4 P0

- [ ] `CONTENT-01`
- [ ] `CONTENT-02`
- [ ] `ASSET-01`
- [ ] `ASSET-02`
- [ ] `PROMO-01`
- [ ] `PROC-02`
- [ ] `PROC-04`
- [ ] `MS-AS-PATCH-READINESS-PIN`
- [ ] `MS-GR-REPLACEMENT-STATE`
- [ ] `MS-GR-WORLD-LIFECYCLE`
- [ ] `SCRIPT-06`
- [ ] `SCRIPT-07`
- [ ] `SCRIPT-08`
- [ ] `SCRIPT-16`
- [ ] `TIMER-02`

#### Packet 4 P1

- [ ] `CONTENT-03`
- [ ] `CONTENT-04`
- [ ] `CONTENT-05`
- [ ] `CMD-02`
- [ ] `SET-02`
- [ ] `LLM-01`
- [ ] `PROC-01`
- [ ] `PROC-03`
- [ ] `PROC-05`
- [ ] `EQUIP-01`
- [ ] `MS-AS-PLUGIN-TRUST`
- [ ] `MS-AS-DRY-RUN-ISOLATION`
- [ ] `MS-AR-DRAFT-CONCURRENCY`
- [ ] `MS-GR-EQUIPMENT-BODY-LAYOUT`
- [ ] `SCRIPT-05`
- [ ] `SCRIPT-09`
- [ ] `SCRIPT-10`
- [ ] `SCRIPT-11`
- [ ] `SCRIPT-13`
- [ ] `PLUGIN-01`
- [ ] `CP-01`

### Packet 5: Gameplay And Player Experience

#### Packet 5 P0

- [ ] `TENANT-03`
- [ ] `EDGE-05`
- [ ] `SESSION-02`
- [ ] `SESSION-03`
- [ ] `CMD-04`

#### Packet 5 P1

- [ ] `CMD-03`
- [ ] `CMD-05`
- [ ] `MOD-01`
- [ ] `MS-GR-COMMUNICATION-ORCHESTRATION`
- [ ] `MS-SOCIAL-RELATIONSHIP-AUTHORITY`
- [ ] `MS-SOCIAL-HISTORY-DURABILITY`
- [ ] `MS-SOCIAL-OBSERVER-SHOUT-POLICY`
- [ ] `PLAYTEST-01`
- [ ] `LIFE-01`
- [ ] `PLAYER-01`
- [ ] `SAFETY-01`
- [ ] `COMMERCE-01`
- [ ] `SOCIAL-01`
- [ ] `MS-PO-MODERATION-APPEALS`

#### Packet 5 P2 And P3

- [ ] `FRONT-01`
- [ ] `MCP-01`

### Packet 6: Operations And Delivery

#### Packet 6 P0

- [ ] `COMPLIANCE-01`
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
