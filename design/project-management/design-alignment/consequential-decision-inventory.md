# Consequential Design Decision Inventory

Status: Complete and independently coverage/fidelity-audited. This artifact is non-normative and ready for the human-led adversarial review.

This inventory identifies important explicit and implicit product and architecture decisions in canonical FireMUD design. It prepares evidence for a later adversarial review run manually by the human decision owner. Automated work on this inventory must not accept, reject, supersede, or resolve a decision; accepted target state remains in canonical design and consequential rationale belongs in an ADR.

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
| Existing ADR set | 11 records plus linked canonical sources | 9 current aliases within the 68 cross-cutting decisions | Prioritized in the source ledgers | Complete initial mapping |
| Cross-cutting system architecture | 22 canonical sources plus ADRs | 68 | Prioritized in the source ledger | Complete and independently audited |
| Microservice architecture | 76 sources | 23 new; stronger evidence for 40 existing keys | Prioritized in the source ledger | Complete and independently audited |
| Specialized runtime architecture | 39 sources | 54 new; stronger evidence for 20 existing keys | Prioritized in the source ledger | Complete and independently audited |
| Product and operations architecture | 35 sources | 38 new; stronger evidence for 11 existing keys | Prioritized in the source ledger | Complete and independently audited |
| **Total unique decision keys** | **All 184 architecture artifacts classified; 172 decision-scan sources plus 12 decision-registry artifacts** | **183** | **Prioritized by source ledger** | **Complete and independently audited** |

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
| `SET-01` | The target settings precedence includes presets and caps that the current effective-settings implementation does not yet resolve. |
| `CONTENT-05` | Resolved baseline: first-party authoring uses Game Design-owned revision and domain APIs; bulk JSON import/export remains deferred until it has a validated package contract. |
| `SESSION-04` | The invisible non-edge reconnect target and current visible restart/reconnect behavior are not one testable promise. |
| `SESSION-08` | Resolved baseline: absolute session validity, disconnected-resume eligibility, and transcript retention are separate policies; resume uses the stricter remaining session lifetime and configured resume window. |
| `SEC-02` | JWT rotation, overlap, pruning, and hot reload are target design while implementation readiness remains incomplete. |
| `OPS-04` | Player-facing restore requires coordinated region pause and traffic-open evidence that current implementation cannot yet prove. |
| `CMD-STATUS-01` | The current narrower gameplay-command status API and target terminal command-lifecycle surface do not yet converge. |
| `TRACE-01` | Named gameplay spans and scoped incident sampling are target capabilities, while current implementation supports only a narrower tracing surface. |

These are inventory findings, not decisions made by this workstream. They remain inputs to the later human-led adversarial review and explicit human resolution.

## Adversarial Review Queue

The queue will be ordered after inventory by blast radius, reversibility, evidence weakness, and degree of missing human consultation. Each review must preserve the current choice's strongest argument and construct the strongest credible opposing case before recommending acceptance, revision, deferral, or supersession.
