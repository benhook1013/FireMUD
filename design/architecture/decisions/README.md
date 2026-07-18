# Architecture Decision Records

Architecture decision records explain why consequential FireMUD product and architecture choices were accepted, superseded, withdrawn, or rejected. They supplement canonical architecture but do not replace it: current target-state behavior remains defined by the linked canonical design documents.

## Status Rules

- `Accepted` records explain current consequential choices and must remain aligned with canonical design.
- `Superseded` and `Withdrawn` records are historical context only and must identify the replacing decision.
- `Proposed` records are not current target state until explicitly accepted and reflected in canonical design.
- A new ADR is warranted for a cross-cutting, authority-setting, security-sensitive, expensive-to-reverse, or genuinely contested decision. Routine local implementation choices belong in code and the owning design document.
- Changing an accepted decision requires explicit human design review, a new or superseding ADR, and updates to every affected canonical design source.

## Registry

| ADR | Status | Primary capability | Secondary capabilities | Decision |
| --- | --- | --- | --- | --- |
| [ADR 0001](./adr-0001-scripting-event-ingress-idempotency-identity.md) | Accepted | `AS-1` | `SF-1`, `SF-2` | Canonical scripting trigger identity and retry deduplication boundary |
| [ADR 0002](./adr-0002-automation-handoff-reliability-and-success-semantics.md) | Accepted | `AS-1` | `GR-1`, `SF-2`, `PO-4` | Durable automation-to-tick handoff and success semantics |
| [ADR 0003](./adr-0003-reload-backpressure-and-retry-contract.md) | Accepted | `AS-1` | `AR-3`, `GR-1`, `PO-4` | Reload backpressure, bounded retry, and timer behavior |
| [ADR 0004](./adr-0004-gameplay-reroute-vs-backend-unavailable.md) | Superseded by ADR 0007 | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Historical distinct reroute close taxonomy |
| [ADR 0005](./adr-0005-tenant-identifiers-in-gameplay-protocol.md) | Accepted | `AA-3` | `EA-1`, `SF-1` | Internal tenant identity and player-facing world selector boundary |
| [ADR 0006](./adr-0006-gameplay-shard-routing-key-transport.md) | Withdrawn; superseded by ADR 0007 | `PO-2` | `AA-3`, `GR-1`, `SF-1` | Historical client-carried gameplay shard routing proposal |
| [ADR 0007](./adr-0007-edge-sharding-and-close-taxonomy.md) | Accepted | `PO-2` | `AA-2`, `GR-1`, `PO-4` | Shard-unaware edge and unified client-visible close taxonomy |
| [ADR 0008](./adr-0008-multi-cluster-gameplay-sharding-scope.md) | Accepted | `GR-1` | `PO-2`, `PO-3`, `SF-2` | Single-cluster gameplay execution and multi-cluster adoption gate |
| [ADR 0009](./adr-0009-coordination-redis-ownership-boundary.md) | Accepted | `SF-2` | `AA-2`, `GR-1`, `AS-1` | Coordination Redis ownership and participation boundaries |
| [ADR 0010](./adr-0010-tcp-proxy-identity-canonicalization.md) | Accepted | `SF-1` | `PO-2`, `PO-3` | TCP Proxy URI SAN identity and constrained fallback modes |
| [ADR 0011](./adr-0011-gameplay-session-front-end-and-region-execution.md) | Accepted | `GR-1` | `AA-2`, `SF-1`, `SF-2`, `PO-2` | Session front-end and fenced lease-owner execution model |
| [ADR 0012](./adr-0012-settings-value-precedence-and-constraints.md) | Accepted | `AR-2` | `EA-1`, `GR-1`, `SF-2` | Settings value precedence, source eligibility, and separately enforced constraints |

Capability identifiers are defined in the [FireMUD Product Capability Taxonomy](../product-capability-taxonomy.md).

## Record Shape For New Decisions

New ADRs should include:

- status and decision dates;
- primary and affected capabilities;
- decision owner and consulted decision makers;
- context, constraints, assumptions, and decision drivers;
- the accepted decision stated without historical alternatives mixed into it;
- strongest credible alternatives, including doing nothing where meaningful;
- positive and negative consequences;
- reversibility, lock-in, exit strategy, and revisit triggers;
- security, operations, cost, and implementation/proof obligations;
- canonical design links; and
- structured supersession relationships when applicable.

Alternatives and rejected hypotheses are non-normative. The accepted decision and canonical architecture must remain the only current target-state contract.
