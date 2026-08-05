# Capability Implementation Reconciliation Snapshot

Status: Frozen point-in-time evidence as of 2026-08-05.

This record preserves the completed automated reconciliation from the product capability taxonomy through the permanent implementation trackers. It is not a live parallel tracker. Product requirements and observable behavior remain under [`design/product`](../../product/README.md), technical contracts remain under [`design/architecture`](../../architecture/README.md), and current implementation and verification states remain in the [ten domain trackers](../implementation-tracking/README.md).

## Snapshot Status

- All 79 leaf capabilities have exactly one primary implementation tracker.
- All ten tracker domains have primary ownership and explicit secondary handoffs in the [capability allocation](../implementation-tracking/capability-allocation.md).
- Every leaf has separate implementation and verification states, canonical design links, production anchors, proof anchors, handoffs, and a remaining gap or decision.
- Snapshot implementation totals are 7 `implemented`, 70 `partial`, and 2 `not-implemented`.
- Snapshot verification totals are 53 `proven`, 14 `audited`, 11 `drift-found`, and 1 `unverified`.
- At the snapshot boundary, `AUTO-01`, `CONTENT-05`, and `SESSION-08` had one reconciled merged baseline each. Later human-reviewed outcomes remain non-canonical until selectively imported to `develop`.

## Snapshot Cross-Domain Boundaries

- Account identity is global; tenant membership, authorization, entitlements, and playable-state admission are explicit scoped checks rather than inferred from account ownership.
- Game Design owns revision, publication, and activation policy, while World and Entity services retain their domain-owned Draft mutation and runtime-state boundaries.
- Admission pointers and explicit routing bundles carry runtime target identity; consumers fail closed rather than reconstructing current scope from partial identifiers.
- Game Session owns player transport/session orchestration, Game Logic owns cross-domain gameplay aggregation, and domain services remain authoritative for their persisted state.
- Durable command, effect, scripting, and scheduler work preserve explicit identity and replay fencing; a replay hit must not bypass request-shape or scope validation.
- Accepted [ADR 0012](../../architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md) owns the target settings contract for platform defaults, preset expansion, operator settings, caps, and tenant/game overrides through canonical effective-settings readers. Current implementation still combines service/operator defaults with persisted overrides; preset expansion and operator caps remain implementation gaps under `SET-01`.
- gRPC application errors remain normal-response `ErrorDetail` values, while transport errors are reserved for infrastructure failures.
- Operational evidence, deployment gates, recovery, and audit records are platform responsibilities fed by domain-owned health and lifecycle signals.

## Snapshot Cross-Domain Gaps

These were implementation or proof gaps at the snapshot boundary, not silently competing target states. Use the owning domain tracker to determine their current state.

- World-owned location and occupancy, Entity-owned actor state, and Game Session bindings still need a fully converged room/location identity and snapshot contract.
- Publication and activation still need complete same-commit validation across design, assets, scripts, schemas, runtime readiness, cutover, and rollback.
- General action targeting, checks, effects, combat, progression, and economy remain materially incomplete beyond the bounded live substrates.
- Player social, presence, moderation, communication, and safety capabilities have working service seams but incomplete integrated player experiences.
- Reconnect and durable transcript substrates have focused proof, but the effective disconnected-resume window is not yet enforced by the `PLAY` admission path. Non-edge restart invisibility and post-logout projection also remain incomplete; full-history export is deliberately future work.
- Commerce and runtime entitlement reads exist, but provider fulfillment, purchased-entitlement lifecycle, quotas, donations, fees, and complete enforcement remain partial.
- Multi-node session routing, regional execution, scheduler leadership, offline recovery, and downstream replay consumption need broader implementation and operational proof.
- Player, creator, and operator applications remain partial scaffolds or API surfaces rather than complete first-party experiences.
- Settings precedence and constraint policy are accepted under [ADR 0012](../../architecture/decisions/adr-0012-settings-value-precedence-and-constraints.md); `SET-01` remains incomplete because current readers combine only service/operator defaults and persisted tenant/game overrides, while target preset expansion and operator caps are not implemented.

## Enforcement

[`check-implementation-capability-tracking.py`](../../../dev-tools/validation/check-implementation-capability-tracking.py) enforces taxonomy/allocation/tracker coverage, primary ownership, state vocabularies, required evidence cells, valid capability references, and local link integrity. It runs through the existing architecture-document contract suite.

## Ongoing Convergence

Current priorities and capability states belong in the domain trackers and [Project Shape History](../project-shape-history.md), not in this frozen snapshot. The completed human-led review is preserved in the source archive; accepted outcomes become canonical only through selective ADR and design imports to `develop`.
