# FireMUD System Architecture: Transaction Strategies

This document explains how FireMUD coordinates data consistency across its independent microservices. It distinguishes between **real-time gameplay commands** (executed within ticks using Redis), **short synchronous cross-service orchestration** (executed through the shared `common-saga` helper), and **long-running durable control-plane workflows** (executed through Temporal). It clarifies when each pattern is needed — and when it is not.

Script evaluation and pin transitions are not a new transaction substrate. The scripting contracts own exact-pin admission, stage-aware recovery, and DSL lifecycle; this document owns the transaction consequence that every script-emitted gameplay effect uses the ordinary tick `EffectId` plus the owner-local transaction, outbox, or durable-intent boundary selected for that effect. **Target state:** a rollback epoch fence rejects stale effects without converting ordinary gameplay into a routine global transaction pause. See [Scripting Contracts](./system-architecture-scripting-contracts.md) and [Runtime Execution](./system-architecture-scripting-runtime-execution.md).

## Implementation Status

The structured participant-guard request, deterministic command-plan root identity, shared `IdempotentEffectExecutor`, standardized tick-effect outcome metric, and replay-verification helper described below are target-state contracts. The current replay tables and helpers remain narrower and domain-local; complete propagation and validation of the plan's persisted `planOrdinal`/root `EffectId`, typed operation, target aggregate, immutable request digest, complete participant set, and shared helper/metric behavior are not yet fully implemented or proven. The current scripting handoff also does not carry, persist, or enforce `scriptPinEpoch` end-to-end, so it cannot yet reject same-version work from an older epoch; this is an implementation gap rather than a weakened target invariant. The target contract remains authoritative: matching requests may replay safely, while an operation, target, or digest mismatch fails closed.

Lifecycle and authoring workflows follow the same local-transaction boundary. World Management commits its lifecycle row/epoch with database compare-and-set; Temporal coordinates retries and waits but is not a transaction or authority. Replacement cutover follows the canonical [PREPARING-to-ACTIVE proof sequence](./system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) and [Game Session cutover boundary](./microservices/game-session-service/api-contracts.md#world-lifecycle-and-admission-boundary): World owns the lifecycle CAS and one-shot cutover hold, while the Game Session owner-local cutover transaction validates the hold id/fence, expected pointer version, and exact source and target `ACTIVE` proofs before its pointer CAS. World finalizes the hold only after authoritative Game Session readback proves the local pointer/audit/prepared-execution/source-cleanup/drain-fence transaction; no global transaction spans the services. A multi-owner Draft commit carries the complete [ADR 0129](./decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md) binding, including target `tenantId`/`versionId`, request or proposal identity, exact `baseCommitId`, canonical revision order, proposed input/digest, and the complete affected `(owner, aggregateId, scopeId, epoch)` set. It performs compare-and-set in each owner database, records durable per-owner outcomes, and publishes through a synchronized read fence; it never uses a global epoch, silent merge, or distributed transaction. These are control-plane workflows and do not add a global ACID path to ordinary gameplay.

---

## Terminology Clarification

| Term | Meaning |
| --- | --- |
| **Command** | A gameplay action issued by a player or AI (e.g., attack, move, use item). Executed inside a tick as a **self-contained gameplay unit** that may touch multiple services but is coordinated via Redis and idempotent domain handlers. |
| **Transaction** | A unit of work that must either fully succeed or be rolled back **within a single service boundary** (for example, a PostgreSQL transaction in Entity Management). Gameplay commands are composed of one or more such local transactions plus idempotent retries; there is **no global, cross-service ACID transaction** for a command. |
| **Tick** | A scheduled gameplay loop slice. Each tick selects at most one root actor action per eligible entity while separately bounding passive/inbound effects and already-admitted effect retries. Redis coordinates staging/cleanup and fairness; ticks are not atomic across all work, and each action/effect is an independent composition of local transactions and retries. |
| **Saga** | A short-lived, synchronous cross-service orchestration composed of multiple local transactions plus optional compensation. Used only for **non-gameplay** workflows that can complete in one caller-owned execution path and do not need durable timers, restart-safe continuation, or operator-visible in-flight state independent of one JVM lifetime. |
| **Temporal workflow** | A long-running, durable control-plane workflow. Used when orchestration must survive restarts, wait durably for time or external events, or expose explicit operator-visible workflow state/history. |

---

## In-Game Command Transactions (No Sagas Needed)

### Command Atomicity and Outcome Aggregation

[ADR 0053](./decisions/adr-0053-command-atomicity-by-invariant-class.md) owns command classification and player-visible command-result semantics. Every command declares required and optional effects, permitted terminal combinations, whether `PARTIAL` is intentional, and any stronger-atomicity routing. `SUCCESS` requires every required effect to be durably `APPLIED` or confirmed by stored evidence as an idempotent replay/no-op, with the player-visible result committed; unresolved required work produces a nonterminal player-visible `PENDING` result; conceptual `FAILURE` proves no required mutation or a local rejection without commit and maps at the command/status boundary to serialized `gameplayResult=FAILED` under [ADR 0016](./decisions/adr-0016-canonical-gameplay-command-status-lifecycle.md); `PARTIAL` is permitted only as a declared game-rule result. Optional failure can coexist with success only when classified before execution. These conceptual outcomes remain distinct from the serialized status vocabulary and execution-convergence fields.

Commands whose temporary partial state could violate unique ownership, value conservation/non-negative balance, conditional exchange, irreversible consumption, premium entitlement, or external commitment do not use independent tick effects. They use one co-located authoritative transaction where possible, otherwise a durable reservation/escrow workflow with idempotent steps and transactional outbox delivery. Routine distributed two-phase commit is not a gameplay pattern.

Before tick staging, Game Session materializes the command's deterministic effect plan as the ordered set of logical root effects admitted for that command. The frozen typed command or `ResolvedEffectPlan` owning command/action contract supplies semantic order; built-in commands declare an equivalent stable order, and ambiguous or duplicate order fails before admission. Game Session persists the ordered plan manifest and digest. Each logical operation has one root `EffectId` and any number of participant guards beneath it. Single-root plans use `planOrdinal=0`, multi-root plans use stable zero-based `0..n-1`, and zero-effect plans allocate neither an ordinal nor a root. The durable gameplay command row freezes/binds its request fingerprint and resolved runtime scope before allocation. Game Session owns an opaque allocation row unique on `(tenantId, gameInstanceId, commandId, planOrdinal)`; it also binds the ordered plan manifest digest and validates that frozen command/runtime binding in the same local durable plan/staging transaction. Reuse requires the same command row identity, frozen command/runtime binding, `planOrdinal`, and manifest digest; any mismatch fails closed. For Automation, the complete applicable Command-Handoff Identity, including optional distinct target runtime fields when present, is the admission/deduplication identity and must exact-map to one durable target `commandId`; after that mapping, allocation uses the target command row's same canonical key. Trigger Identity and `scriptEventId` remain handler/correlation identities, not allocation inputs. A conserved multi-participant operation normally shares one root, but its atomicity still comes from the applicable ADR 0053 co-location or reservation/escrow boundary; the shared root is not global transaction authority. `tickBatchId`, mutable command text, `effectKey`, source ordering, participant tuples, and generated child ordinals cannot substitute for the durable command row identity or allocation binding; no global allocator is introduced.

All real-time gameplay logic — movement, combat, item use, AI — is executed inside **ticks**. Each command is:

- Pulled from the command queue
- Executed using deterministic game logic
- Staged in Redis with Lua-based staging and cleanup/abandon semantics
- Applied via one or more **service-local transactions** guarded by effect identity
- Automatically retried on failure (for example, lock contention or transient errors)
- Reported through a durable command-status surface keyed by `(tenantId, gameInstanceId, commandId)` that persists both execution convergence (`executionOutcome`) and the player-visible command result (`gameplayResult`) independently of Redis coordination state

For the command classes covered by its declared ADR 0053 semantics, the player-visible command result reflects the command’s required and optional effects, permitted terminal combinations, and any intentional `PARTIAL` outcome. `PENDING` is nonterminal while required work remains unresolved. That result contract is not a blanket claim that every distributed gameplay command is globally or player-visibly atomic: independent effects may be temporarily partial while unresolved required work remains `PENDING`, and stronger-routing classes use the co-located transaction or reservation/escrow boundary above. The implementation relies on:

- **Per-service atomicity** – each participating service wraps its own changes in a local database transaction.
- **At-least-once delivery + idempotency** – a durable claim establishes recoverability, but at-least-once physical execution begins only once the work is staged or otherwise dispatchable; idempotent guards ensure repeated applications converge to the same logical outcome.
- **Lane-preserving retries** – actor-action retries retain the original action identity and compete only in the actor-action lane; passive/effect retries retain their effect identity and lane, without granting a new root actor action.
- **Fenced replay** – recovery validates the current region epoch and durable executor fence before replay or cleanup; lease loss routes work through the takeover/reconciliation contract owned by the tick architecture.
- **Eventual cross-service convergence** – if different services commit at slightly different times, domain invariants converge as retries and reconciliation complete; there is no single ACID boundary spanning them.

Within those declared command semantics, this model provides:

- **Player-visible command-result aggregation, including nonterminal `PENDING` and intentional `PARTIAL` outcomes rather than universal global atomicity**
- **Tick-level fairness and isolation**
- **Crash-safe, replayable execution through idempotency**
- **No need for Saga orchestration inside the tick loop**

For cross-region gameplay commands, this also means:

- The origin tick commits once its own local effects and durable remote follow-up creation have reached terminal tick-batch outcomes.
- Any waiting for remote completion, timeout handling, or aggregation of multi-region results lives in a separate durable coordinator record and is resolved by later tick work, not by keeping the original origin tick open.
- Cross-region follow-up/result records are authoritative over Redis hints. Result admission and timeout arbitration occur in one origin coordinator transaction and lock domain: a result admitted before arbitration wins, a timeout terminal outcome is immutable, and any later result is recorded separately as late. Origin deadlines are `(originRegionEpoch, originTickId)` coordinates and suspend while the origin is `PAUSED` or `STALLED`; an operational maximum-real-wait policy records its reason without claiming gameplay time advanced. Target payloads carry exact identity plus ownership, location, and aggregate-version preconditions; a matching epoch alone is insufficient. See [ADR 0066](./decisions/adr-0066-durable-asynchronous-cross-region-result-arbitration.md).

> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md) and [Redis Architecture](./system-architecture-redis.md) for detail on how ticks provide per-service transactional guarantees and cross-service convergence via idempotency.

### When Gameplay Commands Must *Not* Depend on Global Atomicity

Gameplay features must **not** assume that a command is a single, all-or-nothing ACID transaction across services. In particular:

- Cross-service invariants (for example, “both inventories updated or neither is”) must be enforced via:
  - Idempotent handlers and effect guards in each owning service.
  - Clearly defined reconciliation behavior if some services succeed and others fail.
- Designs must tolerate small windows where some, but not all, side effects of a command have committed, as long as retries and reconciliation converge to the intended state.

If a proposed gameplay feature truly requires stronger semantics than this model (for example, a hard guarantee that a multi-service trade never produces a momentary imbalance), it should either:

- Be redesigned to fit the idempotent, eventually consistent tick model, or
- Be treated as a **tick-adjacent workflow** whose short `common-saga`/outbox orchestration handles only post-tick business or external side effects; it never replaces tick gameplay effect guards/replay and accepts higher latency and operational complexity.

### Tick Effects Are At-Least-Once: Idempotency Is Mandatory

Tick execution is replayable: retries, failover, and Redis AOF replay can cause the same logical effect to be attempted more than once. For gameplay commands this is expected and safe only because tick-invoked domain mutations are required to be idempotent with respect to the stable root `EffectId` defined by the identifier glossary.

- **Target-state identity and guard contract:** The deterministic command-plan allocation above is the canonical command-root contract. Each participant guard is uniquely derived from exactly the mutation's persisted `EffectId`, typed operation, and target aggregate; root effects use the root `EffectId`, while generated child mutations use their deterministic persisted child `EffectId` and retain the enclosing root as lineage and reconciliation context. The guard row binds the immutable request digest and stores mutable outcome/evidence state under CAS. Matching replay returns the stored result, while an operation, target, or digest mismatch fails closed.
- **Post-abandon re-drive:** A fresh root is not an ordinary retry or replay. After conclusive `ABANDONED` evidence and source terminalization, a command re-drive creates a new durable `commandId` linked to the prior command/effect lineage before allocating its later-coordinate plan root; reusing the old `commandId` returns its terminal result. Non-command sources use their owner-specific linked re-drive identity. Until the new allocation commits, the work remains recovery work and is not returned to an ordinary queue.
- **Command/effect boundary:** An accepted command lost before durable staging terminalizes only at command scope as `executionOutcome = LOST_BEFORE_STAGING` and `gameplayResult = NOT_APPLIED`; no effect ledger row is invented. A durable pre-staging source/effect claim establishes recoverability but does not itself make physical execution at least once; only a staged or otherwise dispatchable effect enters physical execution, after which its immutable identity/digest guard permits at most one logical mutation. Staged effects terminalize as `APPLIED` or `ABANDONED` only when authoritative evidence permits; inconclusive work remains `SCHEDULED`/reconciliation-required, explicitly including timeout, retry exhaustion, or missing coordination. `REPLAY_NOOP` is an `APPLIED` reason, never a third status. Command `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, and `NOT_APPLIED` remain derived player-result vocabulary under ADR 0053. See [ADR 0069](./decisions/adr-0069-at-least-once-effect-execution-with-one-logical-terminal-outcome.md).
- **Single propagation contract:** The coordinator sends the mutation's persisted root-or-child `EffectId`, typed operation, target, immutable request digest, and required-participant context; generated children also carry their enclosing root/parent lineage binding. Game Session retains the command root's associated `planOrdinal` in its plan/ledger binding. Participants validate the complete binding and atomically commit their guard with effect-visible rows before acknowledging. They do not generate a fresh identity or bind a guard to mutable payload.
- **Current live implementation gap:** The live Game Session proto does not yet carry `commandOrdinal`, the complete applicable Command-Handoff Identity, or the deterministic plan manifest/opaque root allocation binding for this handoff. `TickStagingService` currently derives `effectKey` from `commandId`, falling back to a hash of command text plus the staging slot when no command id is available. That current fallback is not the target-state automation handoff or command-plan identity and must not be documented as though the complete Command-Handoff Identity or root allocation binding were already enforced live.
- **Effect-key boundary:** `effectKey` remains a source-specific descriptor, ordering, or correlation field under its owning contract; it is not the root allocation identity. Existing source-specific validation or serialization may remain where already defined. Root allocation uses the opaque Game Session binding on the durable command row's `(tenantId, gameInstanceId, commandId, planOrdinal)` key plus its frozen command/runtime binding and ordered plan manifest digest; retries reuse the stored binding, and any mismatch fails closed.
- Owning services implement durable idempotency guards (unique constraints, monotonic updates, transactional outbox) under the derived participant guard identity. Duplicate matching attempts become prior-result/no-op outcomes; a guard conflict fails closed. Each service persists its guard outcome and any outbox/reconciliation reference, while Game Session aggregates required participant outcomes under the root `EffectId`.
- **Generated effect chains:** Generated children carry immutable parent/root identity, `depth = parent.depth + 1`, an owner-defined deterministic child ordinal allocated and persisted by the owning contract for replay-stable reuse, and deterministic root-chain count/cost/per-target accounting. When generated work is under an admitted command, it remains beneath that command's root; otherwise it retains its owner/source-specific identity and does not synthesize a command plan. Only a child exceeding a bound is suppressed; committed parents and earlier children remain authoritative. Required/optional authored classification derives truthful `SUCCESS`, `PARTIAL`, or `FAILED` outcomes, and suppression evidence uses bounded metric dimensions with raw identities retained for audit. The owning contract's child-admission transaction must durably map its complete owner scope, enclosing root identity, immutable parent identity, and deterministic child ordinal to exactly one child `EffectId` using CAS, insert-if-absent, or an equivalent atomic boundary. The mapping is persisted before enqueue/apply; exact retries/replays read and reuse the persisted child identity, lineage, and accounting, while any scope, identity, lineage, ordinal, or immutable-request-digest conflict fails closed before enqueue/apply. This source-specific mapping does not replace participant-guard uniqueness (child `EffectId`, typed operation, exact target) or the separate Automation Command-Handoff/global-fanout identity contracts. See [ADR 0075](./decisions/adr-0075-depth-cost-and-count-bounds-for-generated-effect-chains.md).
- For gameplay-visible mutations, derived participant-guard rows backed by the mutation's stable persisted root-or-child `EffectId` are the default idempotency boundary. Simpler `last_tick_id` watermark patterns are allowed only for aggregates explicitly documented as receiving at most one logical mutation per tick.
- **Target-state shared idempotency helper contract (not yet fully implemented or proven):** To keep this contract consistent across services, tick-driven handlers use a shared idempotency helper from `common-data-runtime` (for example an `IdempotentEffectExecutor`) instead of ad-hoc “check or insert” patterns. The helper:
  - **Target-state request shape:** Accepts a structured `ParticipantGuardRequest` containing the mutation guard `EffectId` (`effectId`), typed operation, target aggregate type, target aggregate ID, applicable `playableStateNamespaceId` evidence, and immutable request digest (`requestDigest`), plus callbacks for “apply-if-first” and “handle-replay”. A generated child request also carries its enclosing `rootEffectId` and parent/child lineage binding; the namespace and lineage fields are immutable sealed-context evidence and do not replace `effectId`, operation, and exact target in guard uniqueness.
  - **Target-state full-field replay validation:** Treats an existing guard as a valid replay only when all of those request fields match the persisted guard identity, namespace evidence, and digest. A changed operation, target aggregate, namespace, or digest fails closed and must not invoke the replay callback; omission is valid only for an owning contract whose mutation is explicitly outside playable-state scope.
  - **Target-state guard behavior:** Encapsulates the canonical guard pattern (insert-if-absent, treat conflicts as replay) and throws well-defined exceptions on guard violations.
  - **Target-state standardized metric:** Emits a simple, standardized counter such as `tick_effect_outcome_total{service, effect_type, outcome}` so operators can distinguish first-apply vs replay behavior across services without per-tenant configuration.
- **Replay verification transaction (target state):** Replay evidence is not an audit-only side effect and is not proved by a root effect or one ledger row in isolation. The verifier reconstructs the complete expected concrete participant-projection set from the sealed required-participant and manifest context. It validates every expected participant guard and corresponding durable domain evidence, rejects missing, extra, partial, or conflicting projections, and CASes all required rows transactionally within their owning durable boundaries before the fenced Game Session reconciliation transaction records the result. A stale status, fence/context, missing evidence, incomplete set, set mismatch, or concurrent winner fails closed and rolls back/rejects or retries; external logs and audit streams are projections and cannot authorize a replay transition.

> 🔗 The canonical `EffectId` contract and per-side-effect patterns are defined in [Tick Effect Identity and Idempotency Contract](./system-architecture-ticks.md#tick-effect-identity-and-idempotency-contract).

### Spatial Effects: Location vs Containment (World ↔ Entity)

Movement, drops, pickups, and room presence are cross-service by design:

- **World Management Service** is authoritative for character/NPC location and occupancy, scoped to a `RoomInstanceRef` `(tenantId, gameInstanceId, roomInstanceId)`.
- **Entity Management Service** is authoritative for inventories, containment, and ground items, including synthetic room-ground containers keyed by the same `RoomInstanceRef`.

The durable spatial barrier and attested targeting sequence below are target-state contracts; the current DROP/PICKUP request and focused proof do not yet demonstrate the complete path.

To prevent cross-instance collisions and make retries safe, spatial tick effects must follow these invariants:

- Every spatial effect includes the `RoomInstanceRef` it targets (and, where applicable, `fromRoomInstanceRef` and `toRoomInstanceRef`), not a bare `roomId`.
- The same `EffectId` is propagated to both World Management and Entity Management mutations for the effect, and both services implement durable idempotency guards so partial success can be safely retried.
- A participant acknowledgement is emitted only after that service has durably committed the `EffectId` guard and the effect-visible rows required for its side of the contract. Redis-staged or in-memory state alone is never sufficient to acknowledge convergence.
- Game Session persists (or can deterministically reconstruct) the intended pre/post state for the effect so a reconciliation pass can re-drive the missing side if one service commits and another fails.
- Reconciliation behavior is documented per effect type. The default policy is “retry until convergence” using the original `EffectId`, not “best-effort compensate” with a new effect identity.

#### DROP/PICKUP targeting and actor-fence critical section

`DROP` and `PICKUP` keep the holder mutation in Entity Management, but their World fact read and Entity commit form one Game Session-controlled critical section. Game Session acquires an actor lock keyed by the complete gameplay scope, including `regionId`, and `actorEntityId`, captures the lock's opaque Redis token separately from the current `(regionId, regionEpoch, executorFence)` durable-authority tuple, and, before World validation, durably records one in-flight spatial critical-section barrier keyed by the existing scoped root identity `(tenantId, gameInstanceId, EffectId)` and bound to `actorEntityId`, `regionId`, `RoomInstanceRef`, `regionEpoch`, `executorFence`, and the canonical immutable `requestDigest`. The barrier is coordination/evidence state, not a second effect identity. `regionId` comes from Game Session's durable region authority and is propagated unchanged through World validation and any Game Logic re-resolution under the same root `EffectId` and unchanged `requestDigest`. Game Session retains the actor-lock token/lease and durable fence through the Entity-local transaction while asking World to validate the `TargetingFactSnapshot` location/version evidence. World returns an attestation bound to that same root `EffectId`, actor, `regionId`, `RoomInstanceRef`, `regionEpoch`, `executorFence`, and exact unchanged `requestDigest`.

Game Session renews the actor-lock lease with that same opaque Redis lock token through Entity's commit acknowledgement and passes the attestation unchanged to Entity; the durable `executorFence` is validated separately at the barrier and Entity commit gates. Entity verifies the attestation's exact binding, including `regionId`, against the request, participant guard, and canonical unchanged `requestDigest` for the same root `EffectId` in its local transaction before changing the holder. A missing, mismatched, or stale attestation (including a changed region, epoch, or fence) commits no Entity mutation; the Game Session orchestrator invokes Game Logic to re-resolve the targeting facts under that same root `EffectId`, preserving the unchanged `requestDigest` and `regionId`. A changed request is rejected under the participant guard rather than reusing the root. Barrier creation, renewal, and terminalization are durable, fence-checked transitions; Redis lock expiry cannot clear or terminalize the barrier. A lock-renewal failure or fence change fences the old orchestrator and leaves the durable barrier `RECONCILIATION_REQUIRED`. Handoff/recovery must drain or reconcile that barrier from durable guard/holder evidence; a new owner may reconcile the exact root `EffectId`, but MOVE admission cannot authorize a conflicting actor move merely because the Redis lock expired, and must wait for terminal barrier evidence. Every `MOVE` for the actor must acquire the same actor lock and pass the barrier gate, so it cannot interleave between World validation and Entity commit. After Entity commits, the acknowledgement is recorded, and the barrier and lock are released, a later valid `MOVE` is permitted. This is serialization, durable barrier evidence, and fencing across local transactions, not a distributed World/Entity transaction.

Focused proof must exercise stale location/version evidence, root/actor/room/epoch/fence or request-digest mismatches, Entity rejection before holder mutation, actor-lock lease expiry while the barrier is in flight, an owner crash after World validation and before Entity acknowledgement, lock-renewal failure, executor-fence change fencing the old orchestrator, handoff/recovery reconciliation of the exact root, an old Entity commit racing a new MOVE, and a valid `MOVE` after terminal barrier evidence. For `PICKUP`, a previously moved item is replay only when the stored participant guard matches the same root `EffectId`, immutable request digest, and exact destination container for that actor; another holder or a different destination is a conflict/stale/reconciliation outcome, never a generic replay.

Ambient world mutations (doors, hazards, weather) are treated as spatial effects for replay and idempotency purposes:

- All durable ambient mutations must be issued as effect-shaped commands carrying `EffectId` plus the appropriate instance scope (`RoomInstanceRef` for room-scoped changes).
- World Management is authoritative for ambient state used by gameplay (including hazard activation/inactivation state). Game Logic reads this state through World Management snapshot APIs and must not maintain an independent hazard-authority store.
- Operator tooling and scripts must not bypass this contract by writing instance tables directly; they emit the same effect-shaped commands so retries and crash recovery remain safe.
- Under accepted ADR 0054 semantics, a World ambient mutation, its participant guard, and the World component-version advance commit in one World-local transaction. A matching replay returns the stored guard outcome without applying the mutation again or incrementing the component version again; an operation, target, or request-digest mismatch fails closed. This is a local transaction consequence and does not add a cross-service outcome or alter the DROP/PICKUP ownership boundary.
- The canonical Weather aggregate selector (region-scoped versus room-scoped) remains an explicit unresolved World-owner decision; this transaction contract does not choose it or infer it from current storage. The owner-selected target must still be carried explicitly in the effect-shaped request.

Concrete per-effect required writes and reconciliation rules live in `design/architecture/system-architecture-spatial-and-ambient-effects-catalog.md`. Any new effect must add an entry there before it is used by runtime gameplay.

### Reconciliation Owner of Record (Spatial/Ambient Effects)

Cross-service effect convergence has a single owner of record:

- **Game Session Service** owns reconciliation orchestration and backlog durability for spatial/ambient effects.
- World Management and Entity Management remain owners of their domain writes and idempotency guards, but they do not own cross-service retry scheduling.

Durable backlog contract:

- Game Session persists one row per logical effect in a durable backlog table (for example `effect_reconciliation_backlog`) keyed by `(tenantId, gameInstanceId, effectId)`.
- Minimum persisted fields:
  - `effectType`
  - `targetScope` (`RoomInstanceRef` or region scope)
  - `expectedParticipants` (for example `WORLD`, `ENTITY`)
  - `participantAckState` (per participant applied/pending/final-failure)
  - `firstObservedAt`, `lastAttemptAt`, `attemptCount`, `nextAttemptAt`
  - `status` (`PENDING`, `CONVERGED`, `DEAD_LETTER`)
  - `lastErrorCode` / `lastErrorMessage`
- The reconciliation ledger's `status` is a closed enum of `PENDING`, `CONVERGED`, and `DEAD_LETTER`; it remains separate from the player-visible command result and is not expanded with `PARTIAL` or other command-result values.
- Inserts and status transitions must be idempotent on `(tenantId, gameInstanceId, effectId)` so duplicate scheduling does not create duplicate backlog rows.
- Backlog rows must be indexed at minimum by `(status, nextAttemptAt)` and `(tenantId, status, firstObservedAt)` for retry scans and operator triage.
- For participant ack semantics, `applied` means the owning service can serve the effect through its documented durable read surface for the corresponding fence token. It does not mean `accepted for later batch flush`.

Retry and dead-letter policy:

- Default retry strategy is bounded exponential backoff with jitter and no mutation of `EffectId`.
- Backlog rows remain `PENDING` until all required participants acknowledge applied/no-op for the same `EffectId`.
- Backlog rows receive a `DEAD_LETTER` retry disposition only after retry exhaustion or explicit audited operator action; no destructive compensation is issued from this path. The underlying effect ledger remains governed by the `APPLIED`/evidence-qualified `ABANDONED` contract and remains nonterminal when execution is inconclusive.
- Dead-letter backlog rows remain replayable via explicit operator/API actions; replay must preserve the original `EffectId`.

Retention and lifecycle policy:

- The owning Game Session family must declare its ADR 0163 retention class, terminal/reference/blocker predicate, minimum configured horizon, safe watermark, hold behavior, and bounded GC cadence/batch. Cleanup is eligible only after that predicate and watermark prove that no producer retry, replay, restore, or downstream compatibility path can reference or resurrect the logical effect; nonterminal, inconclusive, and held rows remain blockers.
- Exact retention values are configuration-backed operational policy owned by the [Scaling Runbook](./system-architecture-scaling-runbook.md#data-retention-and-high-churn-tables), not universal durations in this transaction contract. GC remains owner-local, idempotent, and rate-limited per tenant to avoid write spikes.

Required control-plane interfaces:

- `ListEffectReconciliationBacklog(tenantId, status, olderThan, page)` for diagnostics.
- `RetryEffectReconciliation(tenantId, gameInstanceId, effectId)` for explicit replay from `DEAD_LETTER` or stuck `PENDING`.
- `AcknowledgeEffectDeadLetter(tenantId, gameInstanceId, effectId, reason)` for audited operator decisions.
- Logging & Admin dashboards should consume these APIs; operators should not mutate backlog tables directly.

Operational SLOs and alerts:

- Alert when `PENDING` age exceeds 60 seconds for player-visible effect types (`MOVE`, `DROP`, `PICKUP`, `AMBIENT_PATCH`).
- Alert when backlog depth per tenant exceeds configured threshold (for example 1,000 pending rows) or dead-letter count is non-zero.
- Expose metrics at minimum:
  - `effect_reconciliation_pending_total{effect_type}`
  - `effect_reconciliation_age_seconds{effect_type}`
  - `effect_reconciliation_retries_total{effect_type}`
  - `effect_reconciliation_dead_letter_total{effect_type}`

### Tick-Adjacent Workflows (Outbox Boundary)

Some player actions conceptually trigger both in-world effects and “business” side effects such as billing, email, or external webhooks. These **tick-adjacent workflows** must still respect the tick replay model:

- Tick handlers are allowed to:
  - Apply deterministic, idempotent domain mutations guarded by `EffectId` (for example HP changes, inventory moves).
  - Enqueue durable outbox records keyed by `EffectId` into the owning service’s database.
- Tick handlers must not:
  - Call external systems with irreversible side effects (payment processors, email providers, third-party APIs) directly from tick-driven endpoints.
  - Depend on external acknowledgements to decide whether a tick effect was “applied”.

Instead, the recommended pattern is:

- **Inside the tick** – Game Session invokes a domain handler that:
  - Uses the standard idempotency guards (`last_tick_id` or `tick_effect_guard`) for in-world state.
  - Writes a single outbox/event row keyed by `EffectId` when an external workflow should be started.
- **Outside the tick** – A background worker or saga step consumes the outbox row and:
  - Performs the external call(s), with its own idempotency and retry strategy.
  - Updates saga and/or outbox state independently of tick replay.

This keeps tick execution fast, bounded, and safely replayable. A simple owner-local asynchronous continuation uses the owning service's outbox worker; a bounded caller- or worker-owned multi-service continuation may use `common-saga`; restart-independent continuation, durable waits/timers/signals, or operator-managed in-flight state uses Temporal. New designs that mix tick-driven state changes with external side effects must explicitly document this boundary and reference both this section and the tick idempotency rules in `system-architecture-tick-failures-and-operations.md`.

---

## When Short Synchronous Sagas *Are* Used

Short synchronous sagas are used for **non-tick, multi-service workflows** involving persistent state changes that cannot be coordinated via Redis when the orchestration does not need durable workflow execution. These include:

| Use Case | Description |
| --- | --- |
| **Realm entry and actor provisioning** | After explicit realm entry, bind one immutable discovered-entry object and digest to the exact catalog revision, admission pointer, namespace/scope, descriptor or template identity/version, and policy outcome before selecting the Entity-owned policy branch. Retries reuse that unchanged bound resolution and never re-resolve the latest policy; descriptor/template consumption and actor allocation/persistence are technically owned by the [Entity API Contracts](./microservices/entity-management-service/api-contracts.md) and [Entity Runtime and Data](./microservices/entity-management-service/runtime-and-data.md#persisted-actor-and-realm-entry-identity), while Game Session owns realm resolution, selection, and orchestration. [ADR 0140](./decisions/adr-0140-realm-authored-controllable-actor-entry.md) remains the decision rationale. |
| **Short admin remediation** | Limited control-plane actions that touch more than one service but still complete in a single caller-driven request/worker pass |
| **Tick-adjacent outbox follow-through** | Background orchestration around an outbox event when the work is still synchronous and restart-safe continuation is not required |

These workflows:

- Happen **outside the tick loop**
- Modify **persistent storage (PostgreSQL)** across multiple services
- Require compensation and persisted step status, but not durable workflow execution

For `explicitRealmEntry`, the accepted policy outcomes are exactly `PLAYER_CREATED`, `AUTO_PROVISIONED`, and `PRESEEDED_ONLY`; the immutable discovered-entry request may carry an optional `selectedCharacterId`, and its digest covers that selection without duplicating policy semantics. For any branch that reads a roster, the durable saga step binds the selected policy branch, Entity-returned roster snapshot identity/digest, and resolved selected character when one exists; retries reuse those applicable values rather than silently following a changed roster. The saga branches on that unchanged bound outcome: `PLAYER_CREATED` first uses Entity's `ListCharactersByAccount` against the unchanged bound target, then evaluates any supplied creation input against that roster. An exactly-one roster auto-selects its actor; if the request supplies `selectedCharacterId`, it must equal that one actor. A multiple-actor roster requires an explicit `selectedCharacterId` that belongs to the unchanged roster/snapshot; missing or invalid selection fails before binding. Any supplied creation input is valid only for a zero-roster `PLAYER_CREATED` branch; after the roster lookup, a nonempty `PLAYER_CREATED` roster rejects its presence before selection, binding, or mutation, while `AUTO_PROVISIONED` and `PRESEEDED_ONLY` reject it before their branch-specific lookup or mutation. A zero roster with no creation input offers/requires creation without mutation, and zero-roster creation never accepts a caller-selected character ID. A submitted creation input with absent required fields, malformed data, or failure to validate against the exact realm-published descriptor and version bound in the discovered-entry object fails closed; and only valid submitted input calls `CreateCharacter` with the unchanged discovered-entry object/digest plus that input. The preliminary roster read is discovery and selection evidence, not concurrency authority; the Entity-owned `CreateCharacter` contract serializes and rereads the zero-roster boundary before allocation. If a durable saga step has already entered `CreateCharacter`, a retry calls Entity with the stored request identity, digest, and canonical input before any fresh roster rejection so a committed operation with a lost response replays its original result. Each mutating branch carries a caller-stable operation request identity derived from its durable forward saga-step identity (`createCharacterRequestId` for `PLAYER_CREATED`, `autoProvisionRequestId` for `AUTO_PROVISIONED`) and a `mutationDigest` over the trusted target, exact descriptor or template identity/version, and canonical `creationInput`; the auto-provision branch uses the explicit absent-input marker defined by [ADR 0140](./decisions/adr-0140-realm-authored-controllable-actor-entry.md). Entity recomputes and binds the digest and operation identity, while `discoveredEntryDigest` remains admission/discovery evidence rather than actor-write identity. A retry reuses the same operation identity, digest, and canonical input and replays the original result; changed target, descriptor/template/version, or input fails closed as an idempotency conflict.

`AUTO_PROVISIONED` requires `selectedCharacterId` to be absent; any supplied selection fails closed before the provisioning call. It calls `AutoProvisionCharacter` with the same immutable discovered-entry inputs plus its stable `autoProvisionRequestId`, `mutationDigest`, and canonical absent-input marker after explicit entry, allowing its owner-defined idempotent existing-actor result. `PRESEEDED_ONLY` calls `ListCharactersByAccount` as a lookup: zero actors returns the pre-seeded-only denial, while a nonzero roster applies the same exact-one-versus-many selection rule without actor mutation. Its durable selection step binds the returned roster snapshot identity/digest and selected character; a retry must validate that exact snapshot and selection and fails closed if the roster changed rather than silently selecting another actor. No branch re-resolves policy or substitutes a different descriptor/template, namespace, scope, roster, or digest. Catalog and admission-pointer identity/semantics remain in [Multi-Tenancy](./system-architecture-multi-tenancy.md), while Game Session owns realm/pointer resolution, selection, and serving orchestration and Entity Management owns descriptor/template consumption and actor allocation/persistence. [ADR 0140](./decisions/adr-0140-realm-authored-controllable-actor-entry.md) remains decision rationale for this boundary; its canonical presence encoding is retained by the auto-provision request above. The Entity API contract names these target surfaces, but the current proto, implementation, and focused proof do not establish this saga.

If a workflow needs restart-safe continuation, durable waits/timers, or operator-visible in-flight state that survives one service lifetime, it should use the shared Temporal substrate described in [Temporal Control-Plane Workflows](./system-architecture-temporal-workflows.md) instead of extending `SagaRunner` toward durable workflow behavior.

### Mandatory Workflow Adopter Classification

Before implementation, every new workflow or tick-adjacent continuation must record its classification and owner in the adopting service document or tracker. The adopter must state the selected substrate, the local idempotency/reconciliation boundary, the negative cases that keep the work out of the other substrates, and focused proof for the relevant failure and retry behavior:

| Work shape | Canonical placement | Minimum adopter proof |
| --- | --- | --- |
| Gameplay ticks and tick-owned effects | Deterministic effect identity, idempotency guards, and reconciliation | Replay/duplicate, lease or restart, and reconciliation evidence for the owner boundary |
| Simple owner-local asynchronous work | Owning-service transactional outbox and background worker | Atomic enqueue, duplicate delivery, worker restart, and terminal/retry evidence |
| Bounded short caller/worker-owned orchestration | `common-saga` | Compensation or convergence, retry identity, and failure-path evidence within one caller/worker-owned execution path |
| Restart-independent continuation, durable waits/timers/signals, or operator-managed in-flight lifecycle | `common-temporal` / Temporal | Workflow identity, restart, wait/signal or timer, duplicate activity, and operator-read evidence |

This classification reinforces the existing boundary; it does not create a new workflow family or require an ADR. Gameplay execution remains outside both workflow substrates, and a service must not use a saga or outbox worker as a substitute for the durable Temporal guarantees listed in the final row.

### Rollback Boundaries by Operation Class

Cross-service workflows must explicitly choose one of the following rollback classes before implementation:

- **Class A (Pre-Activation Compensating Workflow):**
  - Scope: publish-time and pre-runtime workflows where outputs are not yet active for gameplay (for example `PublishVersion` before a version is activated, or world-creation before admission opens).
  - Contract: compensating actions are allowed; workflow failure may roll back durable writes or mark the target version/workflow as `FAILED` with deterministic retry/repair. The implementation may use short synchronous Saga orchestration or an owning Temporal workflow according to the durable-wait and recovery requirements above.
- **Class B (Post-Activation Runtime Convergence):**
  - Scope: tick-driven gameplay and any mutation visible to live players (movement, containment, ambient mutations, live script-trigger side effects).
  - Contract: no destructive cross-service rollback. Effects are retried with the same `EffectId` until convergence; partial success is resolved by reconciliation, not compensation deletes.

Designs that cross this boundary (for example, activation and live mutations in one flow) must split into two phases with an explicit hand-off point from Class A to Class B.

For world creation and similar activation flows, this hand-off point must be a persisted, monotonic status transition (for example `world_instance_status: PREPARING -> ACTIVE`, with `FAILED_PRE_ACTIVATION` as the non-admitted failure terminal state). Compensation is valid only before the transition commits.

### State Ownership and Mutation Boundaries

To keep responsibilities clear across design-time, domain, and runtime services:

- Game Design Service owns version metadata, branches, commits, and revision history but does not own canonical schemas or template rows for worlds, entities, or assets.
- Domain services such as World Management, Entity Management, and Game Design’s asset storage tables own their respective schemas and all versioned/template rows keyed by `(tenantId, versionId)`. They must be able to load every non-Retired version they own even if Game Design Service is unavailable.
- Runtime services such as Game Session and Automation & Scripting own transient tick state (primarily in Redis) and any persistent instance data they create via domain-service APIs (for example world instance rows keyed by `(tenantId, gameInstanceId)`), but they must never write template rows directly.
- All cross-service workflows that change persistent state across more than one service database must either:
  - execute inside a short synchronous `common-saga` flow when caller-owned retry/compensation is sufficient,
  - execute as a durable Temporal workflow when restart-safe continuation, durable waits, or operator-visible in-flight state matter, or
  - be modeled as tick-adjacent outbox-driven flows when initiated from gameplay commands.

In particular:

- Design-time writes to template tables are only allowed via domain services’ Draft APIs invoked from Game Design Service workflows.
- Published templates for a given `(tenantId, versionId)` are immutable; changing behavior for a live game means creating a new version and new game instances (or, for script-only fixes, changing the **script patch selection** according to the hot-reload and pinning rules described below rather than editing templates in place).

### Live Script Patch Boundary

The target deterministic replay contract governs the `(versionId, scriptPatchVersion, scriptPinEpoch)` tuple that was in effect when a given effect was applied; current implementation and proof boundaries are recorded in [Implementation Status](#implementation-status). The rules below define the target durable tuple, replay, and fence behavior.

- The canonical Game Session replay binding is scoped by `(tenantId, gameInstanceId, EffectId)`. Game Session records the `versionId`, exact `scriptPatchVersion`, and `scriptPinEpoch` and seals that execution tuple with the typed operation, exact target aggregate, immutable request digest, complete expected participant set and context, and sealed manifest into durable tick-batch/effect-manifest state alongside `EffectId`, before participant verification. The Game Session-owned effect ledger is terminal/reconciliation state under that binding, not a standalone identity or replay proof: durable replay still uses the sealed context, complete expected participant projections, and owner guards. Logs and optional audit records may project that context but are not its replay authority.
- Tick handlers and script runners must treat the sealed `(versionId, scriptPatchVersion, scriptPinEpoch)` tuple, target aggregate, and complete expected participant set as immutable effect execution context: retries and replays may load the same durable binding for idempotent reconciliation of the same logical effect, even if the instance later moves to a different patch or advances the epoch. Replay verification must match the target aggregate and full expected participant set exactly, rejecting a different target or missing, extra, partial, or conflicting projection; see the [tick coordination exposure contract](./system-architecture-tick-concepts-and-invariants.md#tick-coordination-exposure-contract). A new apply attempt must pass the current authoritative `scriptPinEpoch` fence; if it was admitted under an older epoch, it must be rejected, canceled, or reconciled without applying a stale effect. Missing or mismatched durable execution context fails closed rather than falling back to the instance's current tuple.
- Operational tooling is allowed to change the pinned exact `(scriptPatchVersion, scriptPinEpoch)` tuple for a running instance at well-defined boundaries (for example between ticks or during maintenance), but every committed script pin mutation atomically advances `scriptPinEpoch` before new script admissions proceed, including patch changes, rollback, and same-version epoch-only repin. That change only affects **future** effects. Previously applied effects remain tied to the sealed `(versionId, scriptPatchVersion, scriptPinEpoch)` tuple in durable tick-batch/effect-ledger state alongside their `EffectId`; logs and audit tables may mirror that tuple but never determine replay execution context.
- For plugin-only rollback or update, durable effect staging and replay must also preserve the exact `(pluginId, pluginVersionId, bindingId)` and captured Automation-owned `(pluginActivationEpoch, lifecycleRevision)` runtime-state fence pair through final effect/replay, rejecting displaced work. The activation epoch is monotonic: each committed invalidating plugin transition advances it exactly once, while `DRAINING` does not; the captured pair is revalidated at evaluation, persistence, handoff, staged/final effect, retry, replay, and recovery boundaries under the [plugin version-fencing contract](./system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope). Neither lifecycle field is part of durable effect or command identity. For plugin-owned timer candidates and firings, however, the captured `pluginActivationEpoch` remains a tagged input to the schedule-candidate/firing-claim identity and therefore to `scheduleCandidateId` and its derived `scriptEventId`; `lifecycleRevision` remains non-identity fence evidence. A plugin-only change does not advance `scriptPinEpoch` unless the script pin changes in the same operation.

---

## FireMUD Short Synchronous Saga Architecture

FireMUD uses a **shared short synchronous saga orchestration library**, not a separate microservice.

### Characteristics

- **Orchestration**:
  - Centralized in the **common-saga** library located under
    `services/common-saga`
  - The engine and its shared Flyway migrations live in `services/common-saga/src/main/resources/db/migration/saga`
  - Consuming services expose those migrations through the shared `classpath:db/migration/saga` Flyway location alongside their service-local `classpath:db/migration` chain
  - Hosts define short, synchronous compensation-aware flows declaratively using the fluent API
  - Saga execution is initiated by services that can own synchronous retry/failure handling, but **coordination logic lives in the library**
  - Participating services include **Account**, **Game Design**, **Game Session**, **World Management**, **Automation Scripting**, **Social Groups**, and **Logging & Admin**
  
- **State Management**:
  - All saga state is persisted in the `saga_instance` and `saga_step` tables provided by the common library.
  - These tables reside inside the owning service schema (for example `${serviceSchema}.saga_instance` and `${serviceSchema}.saga_step`) inside **each service’s own database**. Flyway migrations from `common-saga` are applied per service database so saga state stays local to the service that owns the workflow.
  - Tracks in-progress, completed, and failed synchronous orchestration attempts.
  - Supports compensation.
  - Flyway migrations bundled with the library create these tables automatically when consuming services start.
  - `SagaRunner` emits a `sagas.active` metric and attaches a `correlationId` to logs for each orchestration using MDC.
  - Operators monitor progress via the Saga Dashboard (`/sagas` and `/sagas/{id}/steps` endpoints) provided by the [Logging & Admin Service](./microservices/logging-admin-service/README.md). Current endpoints are local-schema-only; any target cross-service view must use read-only per-service status APIs or fan-out and must not read foreign databases or acquire execution authority.

This library is intentionally not FireMUD's durable workflow engine. World lifecycle, publish, and script-patch readiness now use Temporal because they need restart-safe continuation, stable workflow identity, and operator-visible runtime state independent of one service process.
  
- **Execution Model**:
  - Steps are gRPC calls to owning services
  - Helper `GrpcSagaSteps.callWithRetry` wraps gRPC calls with basic retry logic
  - All steps are **idempotent**
  - Every retryable workflow has one durable workflow/request identity derived from stable business identity and workflow scope. Every logical step has a durable guard identity `(workflow/request identity, stable step name, deterministic occurrence key, execution role)` bound to an immutable request digest. Same identity plus same digest deduplicates/replays its recorded outcome; same identity plus a different digest fails closed. Run, process, retry-attempt, and message/delivery IDs are trace metadata only, never authoritative deduplication keys. See [ADR 0078](./decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md).
  - Each step runs inside a local `@Transactional` method for atomicity
  - Compensation logic is registered via hooks
  - Retried automatically or flagged for manual review

### Fluent API Example

```text
explicitRealmEntry(discoveredEntry, discoveredEntryDigest):
  authorizeRealmEntry(discoveredEntry, discoveredEntryDigest)
  switch discoveredEntry.policyOutcome:
    PLAYER_CREATED:
      if create-character step is already entered:
        Entity.CreateCharacter(boundDiscoveredEntry, boundDiscoveredEntryDigest,
          boundCreateCharacterRequestId, boundMutationDigest,
          boundCanonicalCreationInput)  # replay before fresh-roster rejection
        return stored-or-replayed result
      rosterSnapshot = Entity.ListCharactersByAccount(discoveredEntry, discoveredEntryDigest)
      if retrying roster-selection step:
        require rosterSnapshot.identity == boundRosterSnapshot.identity
        require rosterSnapshot.digest == boundRosterSnapshot.digest
      else:
        bind rosterSnapshot.identity, rosterSnapshot.digest, and branch to sagaStep
      roster = rosterSnapshot.roster
      if roster is nonempty and creation input is present:
        fail closed  # only zero-roster PLAYER_CREATED may accept creation input
      else if roster has exactly one actor:
        require no selectedCharacterId or selectedCharacterId == roster[0].characterId
        bind roster[0].characterId as selectedCharacterId to sagaStep
        select roster[0]  # lookup/selection only; no actor mutation
      else if roster has multiple actors:
        resolvedSelectedCharacter = require actor matching selectedCharacterId in unchanged roster/snapshot
        bind resolvedSelectedCharacter.characterId to sagaStep
        select resolvedSelectedCharacter  # lookup/selection only; no actor mutation
      else if selectedCharacterId is present:
        fail closed  # an empty roster cannot validate a caller-selected actor
      else if creation input is absent:
        offer/require creation  # no actor mutation
      else if creation input is malformed or fails exact bound descriptor/version validation:
        fail closed
      else:
        createCharacterRequestId = stableRequestId(sagaStepIdentity, "create-character")
        canonicalCreationInput = canonicalize(creationInput)
        mutationDigest = digest(trustedTarget, descriptorIdentityVersion, canonicalCreationInput)
        Entity.CreateCharacter(discoveredEntry, discoveredEntryDigest, createCharacterRequestId, mutationDigest, canonicalCreationInput)
    AUTO_PROVISIONED:
      require selectedCharacterId is absent
      require creation input is absent
      autoProvisionRequestId = stableRequestId(sagaStepIdentity, "auto-provision-character")
      canonicalCreationInput = ABSENT
      mutationDigest = digest(trustedTarget, templateIdentityVersion, canonicalCreationInput)
      Entity.AutoProvisionCharacter(discoveredEntry, discoveredEntryDigest, autoProvisionRequestId, mutationDigest, canonicalCreationInput)
    PRESEEDED_ONLY:
      require creation input is absent
      rosterSnapshot = Entity.ListCharactersByAccount(discoveredEntry, discoveredEntryDigest)
      if retrying selection step:
        require rosterSnapshot.identity == boundRosterSnapshot.identity
        require rosterSnapshot.digest == boundRosterSnapshot.digest
      else:
        bind rosterSnapshot.identity and rosterSnapshot.digest to sagaStep
      roster = rosterSnapshot.roster
      if roster is zero:
        return pre-seeded-only denial
      else if roster has exactly one actor:
        require no selectedCharacterId or selectedCharacterId == roster[0].characterId
        bind roster[0].characterId as selectedCharacterId to sagaStep
        select roster[0]  # lookup/selection only; no actor mutation
      else:
        resolvedSelectedCharacter = require actor matching selectedCharacterId in unchanged roster/snapshot
        bind resolvedSelectedCharacter.characterId to sagaStep
        select resolvedSelectedCharacter  # lookup/selection only; no actor mutation
  retries reuse the same discoveredEntry, discoveredEntryDigest, branch, applicable roster snapshot identity/digest, and resolved selectedCharacterId; an entered PLAYER_CREATED create step replays Entity before any fresh roster rejection, and each actor mutation reuses its operation request identity, mutationDigest, and canonicalCreationInput, while a changed input or roster snapshot fails closed
```

This is a target-state branch illustration, not current implementation or proof. Compensation applies only to the selected mutating branch; the `PRESEEDED_ONLY` lookup has no actor mutation to compensate.

This design centralizes logic, improves visibility, and avoids coupling orchestration directly into gameplay services.
The `common-saga` module provides a `SagaBuilder` class implementing this pattern. See [Shared Libraries Overview](./system-architecture-shared-libraries.md) for additional details.
Services include the library and the accompanying Flyway migrations exposed via
`classpath:db/migration/saga` to persist saga state in the owning service schema's
`saga_instance` and `saga_step` tables.
Short-saga examples and consequences are documented in the [Game Session Service runtime and data](./microservices/game-session-service/runtime-and-data.md)
and in the Logging & Admin Service README. The [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)
is the Temporal `world-lifecycle` adopter, not a Saga.

### Saga vs Temporal Boundary

FireMUD now has an explicit shared boundary:

- `common-saga` is for short synchronous orchestration that can run inline and does not require durable waiting or restart-safe continuation.
- `common-temporal` is for long-running durable control-plane workflows that must survive process restarts, support durable timers/signals/queries/updates, and expose operator-visible workflow lifecycle.
- Gameplay ticks and Redis-backed runtime coordination remain outside both of these workflow substrates and continue to use the tick/idempotency/reconciliation model.

---

## When Not to Use Sagas

Do **not** use sagas for:

- Gameplay commands (combat, move, cast spell, AI)
- Anything inside a tick
- Transient state managed via Redis
- Tasks that are already retryable via tick rescheduling

Use Redis staging/cleanup + tick retries for fast, fair, and consistent gameplay handling.

---

## Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Shared Libraries Overview](./system-architecture-shared-libraries.md)
- [World Creation Workflow](./microservices/world-management-service/world-creation-workflow.md)
