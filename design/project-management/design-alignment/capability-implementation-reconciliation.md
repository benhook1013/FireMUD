# Capability Implementation Reconciliation

This record summarizes the automated reconciliation from the complete product capability taxonomy through the permanent implementation trackers. It reports status and evidence; canonical target-state behavior remains under [`design/architecture`](../../architecture/README.md).

## Status

- All 79 leaf capabilities have exactly one primary implementation tracker.
- All ten tracker domains have primary ownership and explicit secondary handoffs in the [capability allocation](../implementation-tracking/capability-allocation.md).
- Every leaf has separate implementation and verification states, canonical design links, production anchors, proof anchors, handoffs, and a remaining gap or decision.
- Current implementation totals are 11 `implemented`, 66 `partial`, and 2 `not-implemented`.
- Current verification totals are 47 `proven`, 26 `audited`, 5 `drift-found`, and 1 `unverified`.
- `AUTO-01`, `CONTENT-05`, and `SESSION-08` now have one reconciled canonical baseline each. Their decision-inventory entries preserve alternatives for later human review without presenting the sources as currently contradictory.

## Reconciled Cross-Domain Boundaries

- Account identity is global; tenant membership, authorization, entitlements, and playable-state admission are explicit scoped checks rather than inferred from account ownership.
- Game Design owns revision, publication, and activation policy, while World and Entity services retain their domain-owned Draft mutation and runtime-state boundaries.
- Admission pointers and explicit routing bundles carry runtime target identity; consumers fail closed rather than reconstructing current scope from partial identifiers.
- Game Session owns player transport/session orchestration, Game Logic owns cross-domain gameplay aggregation, and domain services remain authoritative for their persisted state.
- Durable command, effect, scripting, and scheduler work preserve explicit identity and replay fencing; a replay hit must not bypass request-shape or scope validation.
- Settings use platform defaults plus tenant/game overrides through canonical effective-settings readers; local service defaults are not a competing authority.
- gRPC application errors remain normal-response `ErrorDetail` values, while transport errors are reserved for infrastructure failures.
- Operational evidence, deployment gates, recovery, and audit records are platform responsibilities fed by domain-owned health and lifecycle signals.

## Active Cross-Domain Gaps

These are implementation or proof gaps, not silently competing target states:

- World-owned location and occupancy, Entity-owned actor state, and Game Session bindings still need a fully converged room/location identity and snapshot contract.
- Publication and activation still need complete same-commit validation across design, assets, scripts, schemas, runtime readiness, cutover, and rollback.
- General action targeting, checks, effects, combat, progression, and economy remain materially incomplete beyond the bounded live substrates.
- Player social, presence, moderation, communication, and safety capabilities have working service seams but incomplete integrated player experiences.
- Reconnect and durable transcript substrates have focused proof, but the effective disconnected-resume window is not yet enforced by the `PLAY` admission path. Non-edge restart invisibility and post-logout projection also remain incomplete; full-history export is deliberately future work.
- Commerce and runtime entitlement reads exist, but provider fulfillment, purchased-entitlement lifecycle, quotas, donations, fees, and complete enforcement remain partial.
- Multi-node session routing, regional execution, scheduler leadership, offline recovery, and downstream replay consumption need broader implementation and operational proof.
- Player, creator, and operator applications remain partial scaffolds or API surfaces rather than complete first-party experiences.

## Enforcement

[`check-implementation-capability-tracking.py`](../../../dev-tools/validation/check-implementation-capability-tracking.py) enforces taxonomy/allocation/tracker coverage, primary ownership, state vocabularies, required evidence cells, valid capability references, and local link integrity. It runs through the existing architecture-document contract suite.

## Human Review Boundary

The automated alignment work ends after independent evidence-quality validation. The human decision owner then conducts adversarial review of the consequential-decision inventory. Agents may prepare evidence or competing arguments only when explicitly requested; they must not accept, revise, defer, withdraw, or supersede decisions autonomously.
