# ADR 0110: Explicit Opt-In Schedule Continuity Across Script Transitions

## Status

Accepted

## Implementation Status

Explicit opt-in schedule continuity across script transitions is target state. Instance reconciliation includes `pluginVersionId` in its matching key, so a plugin upgrade cannot preserve a declared logical schedule across versions. Reconciliation also resets wall-clock due state from the observed pin time and clears tick due state on each population pass rather than applying a transition-aware resume calculation to preserved rows; existing materialization, filtering, and firing tests do not prove the transition matrix.

## Canonical Design

- [Scripting scheduler and timers](../system-architecture-scripting-scheduler-and-timers.md)
- [Scripting DSL reference and lifecycle](../system-architecture-scripting-dsl-reference-and-lifecycle.md)
- [Scripting examples and patterns](../system-architecture-scripting-examples-and-patterns.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `TIMER-02`
- Primary capability: `AR-3.3` script and plugin transition safety
- Affected capabilities: `AS-1.4`, `AR-1.5`, `GR-1.4`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of interval continuity, schedule identity, patch/plugin transitions, rollback, due-point migration, and one-shot timer correctness
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `TIMER-02`

## Context

An authored interval can either continue its cadence across a patch or plugin transition or begin again under the target version. Inferring continuity merely because two versions contain the same identifier can carry timing state into content that did not intentionally request it. Always resetting, however, loses useful cadence continuity for deliberately stable schedules.

The transition contract therefore needs a safe default and an explicit identity that can survive version replacement without making replaceable patch or plugin versions part of that identity.

## Decision

The default for an authored interval schedule at a script-patch, plugin-version, activation, disablement, rollback, or repin transition is cancel-and-recreate. Reconciliation tombstones the displaced schedule before it can mint more triggers and creates the target schedule with fresh due state when the target still defines it.

Continuity is opt-in. Both sides of a transition must declare compatible continuity for the same logical key, scoped within the tenant and runtime instance:

`{stableOwnerKind, stableOwnerId, scheduleDefinitionId, targetScopeType, targetScopeId}`

For a core script, the stable owner is its durable script identity. For a plugin, it is `pluginId`; replaceable `pluginVersionId` is recorded provenance and execution-fence metadata but is not part of the continuity key. `scriptPatchVersion` is likewise version metadata rather than logical interval identity. Runtime playable scope and binding ownership must still be compatible and fenced even when they are not authored parts of the logical key.

`pluginActivationGeneration` is runtime execution-fence evidence, not continuity identity. An invalidating generation change fences displaced candidates and admitted work under their original identities and never reuses their claims, but it does not by itself deny an otherwise explicitly declared, typed-compatible cross-plugin-version schedule-row and due-state continuity transition. Disablement or revocation without a replacement target resets and tombstones the displaced schedule; a same-version disable followed by re-enable starts with fresh schedule state even when the definitions match.

A schedule is preserved only when both explicit continuity declarations and the logical key match, `source.isDryRun` equals `target.isDryRun`, its interval kind, cadence unit and value, target binding, playable scope, and owner/binding fencing remain compatible, and the match is unambiguous. When those preservation conditions hold, reconciliation retains the logical row and applies the scheduler owner's transition-aware due calculation. An absent declaration, absent target schedule, changed key or cadence contract, explicit reset, disabled/revoked owner without a target, or ambiguous duplicate causes the old row to be tombstoned; when a target still exists on that reset path, it starts as a fresh schedule. Rollback follows the same rules and does not infer continuity from historical similarity.

For a preserved interval, plugin-owned schedules may be preserved only after the complete applicable current plugin evidence set defined by [Scripting Contracts §8](../system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope), including a current match for the captured `pluginActivationGeneration`, is fresh and valid; missing, stale, revoked, contradictory, or unavailable evidence fails closed before preservation. `pluginActivationGeneration` remains execution-fence evidence, not continuity identity. Reconciliation then rewrites the durable patch/plugin ownership metadata to the exact target `(scriptPatchVersion, scriptPinEpoch)` tuple, including applicable `pluginId`, `pluginVersionId`, schedule-owned `bindingId`, and captured `pluginActivationGeneration`, before admission resumes. The same evidence gate applies before any candidate or firing-claim derivation. It calculates the next due point under the normative [Timer Resume Rule](../system-architecture-scripting-scheduler-and-timers.md#timer-resume-rule-normative): tick schedules use committed tick identifiers, while compatible wall-clock schedules retain persisted absolute `dueAt` and regenerate `nextRunAt` strictly as its projection; target resume time does not rewrite `dueAt`. It does not blindly retain an overdue point, restart from deployment time, or replay every missed interval.

`scheduleSemanticsHash` is diagnostic evidence only. It can explain differences and support tooling, but hash equality cannot grant continuity and hash inequality alone does not override an otherwise valid explicit declaration. Cadence, kind, target, and identity compatibility remain typed checks rather than hash inference.

One-shot timers are outside this interval-continuity rule. Their exact due identity, payload, version/epoch fences, cancellation, and any future transfer semantics require their own correctness contract. A matching `scheduleDefinitionId` must not silently migrate or recreate a pending one-shot timer across versions.

Reconciliation completes durably before target-version timer admission resumes. A partial or ambiguous transition remains non-admissible until the schedule set, tombstones, rewritten owner metadata, and due calculations converge.

## Consequences

- Ordinary script and plugin changes cannot accidentally inherit an old interval's phase.
- Authors can preserve deliberately stable long-running cadences across patch, plugin-version, and rollback transitions.
- Default transitions may delay the next firing by one fresh interval, which is intentional rather than treated as missed-work replay.
- Continuity declarations and typed compatibility checks add compiler, publication-validation, reconciliation, and operator-visibility work.
- Plugin schedule persistence must separate stable `pluginId` continuity identity from replaceable `pluginVersionId` provenance and fences.
- One-shot correctness is not weakened by reusing interval migration rules.

## Alternatives Considered

### Preserve Every Matching `scheduleDefinitionId`

Rejected because identifier reuse alone does not prove that the author intended timing continuity. It can preserve stale phase after material changes and makes accidental identifier retention consequential.

### Reset Every Schedule on Every Transition

This is the strongest simpler alternative. It removes cross-version due migration and provides a clear no-old-schedule guarantee. It is rejected as the only behavior because deliberately stable patrols, maintenance intervals, and similar long cadences benefit from explicit continuity without replaying a paused window.

### Infer Continuity from Cadence, Binding, Graph Shape, or Semantics Hash

Rejected because similarity is not logical identity. Small unrelated edits can change a hash, while two distinct schedules can share cadence and target shape.

### Include `pluginVersionId` in the Continuity Key

Rejected because changing plugin version would necessarily create a new identity and make declared cross-version continuity impossible. The exact plugin version remains mandatory execution provenance and a fence, not stable schedule identity.

## Implementation and Proof Obligations

Persist and validate explicit continuity policy, stable owner identity, `scheduleDefinitionId`, target scope, interval kind and cadence, source and target exact script pin tuples, applicable plugin versions, schedule-owned bindings, captured `pluginActivationGeneration`, previous and recalculated due points, transition identity, tombstone/fresh/preserved outcome, and the exact execution fences. Compilation and publication must reject missing required identity, duplicate continuity keys, and incompatible declarations deterministically.

Proof must cover default reset even when `scheduleDefinitionId` matches; explicit typed-compatible preservation across patch and plugin-version changes without using `pluginActivationGeneration` as continuity identity; owner metadata rewrite without changing the preserved row's logical identity; rollback; an invalidating generation change that fences old candidates/work and never reuses claims; absent target; changed owner, identifier, target, kind, cadence, playable scope, binding, or `pluginActivationGeneration`; same-version plugin disable/re-enable with fresh state; disablement or revocation without a replacement target resetting and tombstoning; explicit reset; duplicate definitions; idempotent transition retry without duplicate row or firing; and failure or restart during reconciliation. For plugin-owned schedules, proof must also cover the complete applicable plugin evidence-set freshness/validity gate and missing, stale, revoked, contradictory, or unavailable fail-closed outcomes before preservation and before candidate/firing-claim derivation, following [Scripting Contracts §8](../system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope).

Due-point proof must cover tick and wall-clock schedules with previous due points before, at, and after the resume point; no burst replay of the paused window; and no duplicate firing across an idempotent transition retry. Separate proof must show that one-shot timers are neither preserved nor recreated through this interval rule.

The current implementation and focused proof do not satisfy this decision. Instance reconciliation includes `pluginVersionId` in its matching key, so a plugin upgrade cannot preserve a declared logical schedule across versions. Reconciliation also resets wall-clock due state from the observed pin time and clears tick due state on each population pass rather than applying a transition-aware resume calculation to preserved rows. Existing materialization, filtering, and firing tests do not prove the transition matrix above.

## Reversibility and Revisit Triggers

Declaration syntax, tombstone retention, compatibility vocabulary, and operator presentation may evolve while retaining an explicit opt-in and typed logical key. Revisit the default only if measured authoring and runtime evidence shows that nearly all interval transitions require continuity and tooling can make that intent unambiguous. Define a separate decision before transferring pending one-shot timers across versions; do not broaden this interval rule implicitly.
