# Project Shape History

This append-only record captures occasional dated assessments of FireMUD's overall implementation shape. It is project-management evidence, not canonical design or the live capability-status authority. Current counts and gaps remain in the [domain implementation trackers](./implementation-tracking/README.md); future snapshots should preserve earlier entries so changes in project shape can be compared over time.

## 2026-07-17 - Post-Alignment Pre-v1 Baseline

### Partial Implementation Status

At this snapshot, 11 of 79 leaf capabilities are `implemented`, 66 are `partial`, and 2 are `not-implemented`; verification includes 5 `drift-found` capabilities.

FireMUD is unusually ambitious but technically coherent. It is not merely a MUD server: it is a configurable, multi-tenant game platform with durable execution, authored behavior, operational tooling, and several client protocols. The architecture generally reflects that ambition rather than disguising complexity behind shortcuts.

Its strongest qualities are:

- clear service authority and fail-closed boundaries;
- extensive thought around replay, durability, identity, routing, and recovery;
- soft-configured game behavior rather than one hard-coded game;
- serious pre-v1 willingness to replace weak contracts instead of preserving compatibility; and
- increasingly credible links between design, implementation, and proof.

The main risk is not bad architecture. It is excessive surface area. The system has many credible foundations but relatively few completely converged product capabilities. It can remain nearly complete everywhere without becoming complete in the player-visible paths that matter most.

The recommended response is to resolve known drift first, then finish selected high-value capabilities end to end, prioritizing playable user journeys over additional broad substrate. The detailed baseline and active gaps are recorded in the [capability implementation reconciliation](./design-alignment/capability-implementation-reconciliation.md).
