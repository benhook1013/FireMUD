# ADR 0100: Single-Authority Script Pins with Exact-Version Execution

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `MS-AS-PATCH-READINESS-PIN`
- Primary capability: `AS-1.6` quotas, readiness, reload, and automation runtime operations
- Affected capabilities: `AS-1.2`, `AS-1.5`, `AR-3.3`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of publication, tenant readiness, instance preparation, pin authority, exact-version execution, failure behavior, and rollback

## Context

A published script patch is not automatically safe or selected for a running game instance. Automation & Scripting must first ingest and validate its compiled graphs and complete tenant-scoped readiness work. A tenant-`READY` result means the patch is eligible for instance rollout; it does not identify the patch currently governing any instance.

Some existing reload language nevertheless gives Automation & Scripting a runtime-scope `activePatchVersion` and permits it to keep using that older value after an instance reload failure. That creates two possible authorities after Game Session has committed a different pin. It also leaves execution vulnerable to mutable graph lookup, event reordering, and ambiguous old work.

## Decision

Script patches follow one ordered lifecycle: Game Design publishes an immutable patch; Automation & Scripting validates it and marks it tenant-`READY`; an operator or authorized workflow then explicitly pins that exact patch for a game instance. Publication or tenant readiness never changes a running instance by itself, and runtime never follows a mutable `latest` selection.

Automation & Scripting stores compiled graphs and bindings as immutable, exact-version artifacts. Before pin commit, Game Session may require Automation & Scripting to prepare or preload the candidate and attest that the exact tenant-`READY` artifact is usable for the target instance. Candidate preparation is non-authoritative and must not admit candidate gameplay work. If preparation fails, Game Session does not change the instance pin or its epoch, and the previous pin continues normally.

Game Session is the sole authority for the active per-instance script selection. It atomically persists `(scriptPatchVersion, scriptPinEpoch)` for each `(tenantId, gameInstanceId)` and advances the epoch whenever the selection changes, including rollback or repinning to a previously used version. An idempotent retry of the same committed control-plane request returns the same version and epoch. Automation-owned readiness rows, preload state, caches, and pin projections do not become competing active-version authority.

Every admitted trigger, durable work item, schedule or timer firing, emitted command, and gameplay handoff carries the exact `scriptPatchVersion` and `scriptPinEpoch` under which it was admitted. Automation executes only the immutable compiled graph identified by that version; it does not substitute another graph on cache miss or resolve a local active/latest pointer. Already-started evaluation may finish against its captured immutable graph, but new work under a displaced epoch is rejected. Its later handoff or gameplay effect must still pass the Game Session version-and-epoch fence, so queued or late old work cannot affect gameplay after repin and may be canceled or purged operationally.

After Game Session commits a new pin, failure to load, reconcile, or obtain that exact artifact fails closed for new work and is operator-visible. Automation & Scripting must not silently continue or fall back to its prior locally observed version. Recovery to the prior version is an explicit Game Session repin to that still-`READY`, base-compatible patch, producing a new pin epoch and ordinary audit and convergence evidence.

Tenant readiness and `onLoad` remain pre-pin validation. They may validate graphs and prepare ephemeral or recomputable candidate-local state, but they have no game-instance context and may not emit gameplay commands, mutate instance or entity state, or create other gameplay side effects. Per-instance initialization belongs after explicit pinning in a separately fenced runtime workflow.

Architecture and implementation language that treats an Automation-owned `activePatchVersion` as runtime authority must be removed or redefined as an observed projection or exact-version cache. Automation & Scripting owns tenant readiness and compiled artifacts; Game Session alone owns which ready artifact is active for an instance.

## Consequences

- A publish or tenant-`READY` transition cannot silently change live gameplay, and different instances may deliberately pin different ready patches.
- Failed candidate preparation leaves the known-good pin untouched; failure after commit cannot create an unrecorded fallback state.
- Exact version and epoch propagation make stale events, delayed work, repin-to-the-same-version, and reordered projections fenceable.
- Automation workers must retain or reload immutable versioned artifacts for admitted work and keep projections distinguishable from authority.
- Rollout adds preparation and convergence work, and an exact artifact outage after commit can pause automation until repair or explicit rollback.

## Alternatives Considered

### Automation-Owned Active Patch per Tenant or Instance

Automation could switch a mutable `activePatchVersion` after reload and let Game Session merely report the desired patch. This is rejected because readiness, observed load state, and authoritative runtime selection would be conflated, and the two services could disagree during failures or event reordering.

### Commit the Pin and Fall Back Locally if Activation Fails

Automation could keep executing the prior graph when the newly committed pin cannot load. This preserves availability but is rejected because gameplay would execute a version different from the Game Session record. Candidate preparation handles foreseeable load failures before commit; afterward, fail-closed behavior and explicit repin preserve one auditable authority.

### Resolve Latest Ready or Mutate Compiled Graphs in Place

Runtime could choose the newest ready patch or replace graph contents behind a stable identifier. This is rejected because retries, recovery, concurrent instances, and rollback would no longer reproduce the exact behavior selected at admission.

## Implementation and Proof Obligations

Contracts must define the atomic Game Session pin-and-epoch record, idempotent mutation and compare-and-set behavior, optional exact-candidate preparation evidence, committed pin events and reconstruction reads, and exact version-and-epoch fields through trigger admission, durable work, scheduling, handoff, and gameplay execution. Automation graph storage must reject mutation or ambiguous lookup for an admitted version, and Automation active-version fields must be eliminated or made explicitly non-authoritative projections.

Proof must cover publish and tenant readiness without instance activation; `onLoad` without gameplay side effects; preparation failure leaving the old pin and epoch unchanged; successful pin commit and same-request retry; concurrent repins and reordered or stale events; worker restart or cache miss without version substitution; already-started, queued, and newly arriving old-epoch work; post-commit load or reconciliation failure without fallback; and explicit rollback to a prior tenant-`READY` patch with a new epoch.

Current contracts contain tenant readiness, Game Session pin records, exact patch fields, and version fences, but they do not yet prove a per-instance script pin epoch across every work boundary, prepare-before-commit behavior, immutable exact-version graph selection on every worker, or removal of the contradictory Automation-owned active/fallback model. This decision records the target contract and does not claim those gaps are implemented.

## Reversibility and Revisit Triggers

Preparation mechanics, artifact caching, and operational drain policy may evolve without changing the authority boundary. Revisit only if FireMUD adopts a different live-update model that still provides one authoritative per-instance selection, immutable execution identity, stale-work fencing, deterministic recovery, and explicit rollback with equivalent auditability.
