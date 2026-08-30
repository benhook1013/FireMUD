# FireMUD Tick System: Failures & Operations

This document focuses on **failure modes, recovery flows, and operational guidance** for the tick system.

It is aimed at both developers and operators who need to understand what happens when executors crash, Redis has issues, or ticks must be replayed.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

## Implementation Notes

This document describes the full target-state recovery and operator model. Current implementation and proof status is tracked in [Game Session runtime and tick coordination](../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md). The live substrate is narrower:

- the live durable ownership row is currently `{tenantId, gameInstanceId}`-scoped;
- the live owner/status API is `GetRuntimeOwnershipStatus`, not yet a full region-scoped status surface;
- the live fence token is opaque and compare-and-match based;
- the live `tick_batch` / `tick_effect` ledger is real and now carries the current gameplay-command selected-work manifest on `tick_batch`, including current-boundary `enqueueSeq`, source metadata, and digest-checked replay reuse for surviving staged batches; live selected-work manifests do not yet preserve the required declared `phase`, `lane`, and `cost_class` metadata. The live `enqueueSeq` is allocated by one database-wide sequence; the region-scoped, cross-source allocator defined below is target-state. Timer/retry/remote-follow-up source-claim manifests, region-scoped replay controller breadth, and cross-region result-return semantics also remain target-state follow-through.
- the live gameplay-command staging path does not yet provide the complete target automation handoff identity: it derives `effectKey` from `commandId` and uses a deterministic text/slot fallback when no command ID is available. The target fail-closed contract below assigns the stable root `EffectId` before acknowledgement when possible and rejects before acknowledgement when assignment fails. If an already acknowledged `ACCEPTED_VOLATILE` command cannot receive that identity, `LOST_BEFORE_STAGING` remains limited to eligible `RECEIVED`/`ENQUEUED` work with no surviving batch; batch-bound or inconclusive work remains non-terminal/reconciliation-required and is never evidence-free `ABANDONED`. This document does not claim that target behavior is already live.
- The accepted lane and fencing consequences are target-state requirements in this recovery model: selected-work manifests and replay records must preserve each item's declared `phase`, `lane`, and `cost_class`, while takeover reconciliation remains subject to the Redis lease plus durable executor-fence handshake owned by [Tick System and Runtime Design](./system-architecture-ticks.md). This document does not duplicate that owner protocol.

Naming convention: API, workflow, and EffectId prose uses `regionEpoch`. Snake-case forms such as `region_epoch`, `last_region_epoch`, and `target_region_epoch` are reserved for explicitly identified SQL/storage fields, Redis payloads/keys, or schema examples.

## What This Covers

- Crash recovery and replay behavior.
- Idempotency rules tied to the region-scoped tick timeline `(regionEpoch, tickId)`.
- Handling stuck or partial tick entries.
- Design checklist for new tick-driven commands.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` contain the main failure-handling and operational rules:

- **Crash Recovery and Replay** – how executors recover from failure and resume processing safely.
- **Domain Idempotency Rules (Timeline Context + Owner Guards)** – how `(regionEpoch, tickId)` provide ordering/fence context while owner guards enforce logical effect idempotency.
- **Design Checklist for New Tick-Driven Commands** – review checklist for new commands to ensure they follow tick invariants.
- **Tick Execution and Redis Integration** – failure scenarios and invariants around the canonical commit pattern.
- **Cross-Region Command Execution and Result Relay** – constraints for cross-region retries and replay.

When implementing new failure-handling flows or adding operational procedures, ensure the detailed behavior is captured in `system-architecture-ticks.md` and reflected in the appropriate runbooks (for example, Redis incident runbooks).

## Crash Recovery and Replay

Tick recovery is driven by durable PostgreSQL tick state plus domain-level idempotency rules, with Redis acting only as a volatile coordination layer. Game Session creates the durable PostgreSQL `tick_batch` and `SCHEDULED` ledger rows first; only then may it stage the batch in Redis `pending`. Redis `pending` is staging/acceleration coordination only and is never authoritative for tick intent or recovery.

- On executor crash or failover, a new worker acquires the region lease, re-establishes the authoritative recovery baseline from the durable tick-batch, tick effect ledger, follow-up tables, and the active status/progress surface: current live uses `GetRuntimeOwnershipStatus` plus `ObserveRuntimeTickProgress` for the owner, opaque `executorFence`, and committed `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` progress; target-state uses `RegionStatus`/`GetRegionTickStatus`. It then inspects any surviving `tick:{tenantRegionTag}:pending`, `retry:{tenantRegionTag}`, and timer keys only as optional coordination hints while replay converges from durable state.
- Before replay, cleanup, or takeover adoption, the successor revalidates the same in-memory Redis lease token it acquired and the expected `regionEpoch`, then separately verifies the current durable executor fence under the owner contract before any recovery mutation; a missing or mismatched token, epoch, or fence pauses and enters fenced reconciliation, affects no state, and never persists the raw token. Lane identity is immutable during recovery: actor-action retries remain actor actions, passive/effect retries remain passive effects, and replay cannot mint a new root actor action for the same tick.
- Redis is treated as a volatile coordination layer with **at-least-once** semantics; network retries, executor failover, and AOF replay can all cause the same logical effect to be attempted more than once.
- Domain services rely on `(regionEpoch, tickId)` and effect guards to ensure that replays do not double-apply logical effects even when Redis state is partially lost.

### Durable Commit vs Coordination-Cleared Boundaries

Failure handling assumes the same two-boundary model defined in the main tick design:

- `durable_committed`:
  - Ledger rows for `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId)` are terminal (`APPLIED` or `ABANDONED`) where the existing evidence policy permits terminalization; any inconclusive row remains non-terminal reconciliation work under its original root `EffectId`, and
  - The current-live `GetRuntimeOwnershipStatus` reports the same opaque `executorFence` recorded by the batch, the live `regionEpoch` matches, and the live commit boundary has advanced under that same fenced write. `RegionStatus` is a target-state durable projection in examples where the live ownership/status surface is not available.
- `coordination_cleared`:
  - Redis `pending`/lock coordination for that tick is no longer in flight.

Crash-window behavior:

- Crash before `durable_committed`:
  - Tick work remains replayable; recovery replays remaining `SCHEDULED` effects using idempotent handlers and ledger rules.
- Crash after `durable_committed` but before `coordination_cleared`:
  - Recovery must finish coordination cleanup before allowing the next tick to stage new work.
  - The durable watermark does not regress, and heartbeat chronology does not rewind.

### Tick Effect Ledger and Replay Guarantees

To make replays observable and bounded, Game Session maintains a **tick effect ledger** in PostgreSQL. Conceptually, the ledger captures the same coordination timeline described in the Redis and tick architecture docs:

- Every tick effect that has been durably claimed or staged for execution (for example, rows associated with a tick batch, replay-eligible retry work, or durable follow-up records) is mirrored into a Game Session–owned ledger table (for example `tick_effects`) with columns such as:
  - `tenant_id`, `game_instance_id`, `playable_state_namespace_id`, `playable_state_scope`, `region_id`, `region_epoch`, `tick_id`
  - `tick_batch_id`
  - `root_effect_id` (stable logical root identity retained on every ledger projection)
  - `typed_operation` (canonical operation identity retained alongside the root on every participant projection)
  - `effect_key` (stable, human-readable descriptor passed through from staging)
  - declared `phase`, `lane` ∈ {`actor_action`, `passive_effect`}, and bounded `cost_class`; recovery and replay preserve all three values
  - `target_aggregate_type`, `target_aggregate_id` (required target aggregate identity)
  - `automation_dispatch_id`, `command_ordinal` (both required for script-generated effects; nullable for non-scripting commands)
  - `command_id`
  - `status` ∈ {`SCHEDULED`, `APPLIED`, `ABANDONED`}
  - `reason` / `outcome`
  - replay-verification audit metadata when a replay proves that the effect was already applied (for example verifier, timestamp, and evidence digest/reference)
  - `created_at`, `updated_at`
- Target-state requires one durable tick-batch record per `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`, owned by Game Session, and stores at minimum:
  - `tick_batch_id`
  - `executor_fence` (or equivalent opaque durable fence captured at batch allocation time); this is the canonical durable fence used for compare-and-match protection.
  - exactly one immutable sealed execution-context binding: `sealed_execution_context_digest`, or a durable `sealed_execution_context_ref` whose referenced record contains that digest. The binding covers the batch coordinates, executor fence, selected-work manifest/digest, effect identities, and pinned execution inputs/version/script patch; replay must use the sealed context rather than reconstructing mutable context.
  - `lease_token_correlation` may be retained as optional non-secret lease-acquisition trace/audit metadata; raw Redis lease tokens are never persisted, and this correlation is not an authoritative durable fence and must not authorize batch allocation, commit, cleanup, or recovery writes.
  - `expected_effect_count`
  - `status`
  - selected-work manifest entries for the batch
  - optional `pending_digest` or equivalent Redis-pending integrity field; it is not a substitute for the required immutable exact pending envelope or the sealed execution-context binding above
  - `created_at`, `updated_at`
- The selected-work manifest is required for deterministic replay and source cleanup. At minimum it records, per selected source item:
  - the batch scope `(tenantId, gameInstanceId, regionId, regionEpoch)`; its `enqueue_seq` is allocated from the complete `<tenantId, gameInstanceId, regionId>` scope and is not reset or reused when a reset bumps `regionEpoch` and restarts `tickId` at `0`
  - `source_kind`
  - declared `phase`, `lane`, and `cost_class`
  - source item identity
  - `entity_id`
  - persisted ordering inputs used when the batch was formed: declared `phase`, `lane`, `cost_class`, policy-defined `priority`, normalized `due_tick_id`, entity-local `entity_enqueue_seq`, `source_kind`, stable source identity, `entity_id`, and source-specific `command_id`/`effect_key` where the owning contract requires them. Within an entity, the canonical tuple is `(priority, due_tick_id, entity_enqueue_seq, source_kind, commandId_or_effectKey)`; cross-entity rotating/deficit state and cost accounting are sealed with the selected manifest. The historical final tuple label `commandId_or_effectKey` is one normalized, persisted source-specific stable identity slot: `command` uses `commandId`, `timer` uses the timer member ID, `retry` uses the persisted retry member/effect identity required by its owning contract, `remote_followup` uses the durable follow-up row ID, and `generated_effect` uses its recorded deterministic child `EffectId` under the immutable parent/root identity and child ordinal contract; the selected manifest retains the exact mapping.
  - source-claim/removal state
- Every replayable timer manifest entry must also carry exactly one tagged durable due point, `dueAt:<epochMillis>` or `dueTickId:<value>`, plus the normalized due-tick value used for deterministic ordering and replay. `due_ms` is permitted only as an explicitly derived Redis timer projection; it is never the required durable manifest field or authority.
- Per-source minimum fields are:
  - `command`: `command_id`, queue family, enqueue sequence
  - `timer`: timer member ID, exactly one tagged durable due point (`dueAt` or `dueTickId`), and the normalized due-tick value used for ordering; any `due_ms` value is an explicitly derived Redis projection only
  - `retry`: retry member/effect identity, `retry_count`, `next_eligible_tick_id`
  - `remote_followup`: durable follow-up row ID, `target_region_epoch`, `due_tick_id`
- Recovery tooling may store and inspect richer per-source payloads, but deterministic replay and cleanup must remain possible from the documented fields above.
- Any service-level schema or storage doc that introduces the concrete `tick_batch` / manifest tables must mirror these minimum fields explicitly rather than redefining a narrower contract locally.
- The current live tick-batch table enforces uniqueness only on its durable `tick_batch_id` key (`tick_batch_tick_batch_id_key` / `idx_tick_batch_tick_batch_id`); it does not yet enforce the coordinate tuple.
- Completing the target-state invariant requires a migration that audits and reconciles duplicate coordinate tuples, then adds and proves a unique PostgreSQL constraint/index on `(tenant_id, game_instance_id, region_id, region_epoch, tick_id)`. Until then, the recorded `tick_batch_id`, lease/fence checks, and application duplicate detection are the current safeguards but do not provide database-level tuple uniqueness.
- Batch allocation and every durable tick-control mutation are fenced using the recorded canonical `executor_fence` (or equivalent opaque durable fence). An optional non-secret `lease_token_correlation` is trace/audit context only; raw Redis lease tokens are not persisted, and the correlation cannot substitute for the durable fence.
- Target-state batch allocation is one atomic, CAS-fenced durable operation: the transaction reads the authoritative current `regionEpoch` and `executorFence`, conditionally creates or adopts the coordinate-unique batch only when both still match, writes the batch manifest and initial ledger rows, and commits the durable allocation before any Redis `pending` staging. A changed epoch, lost lease, stale fence, or competing owner causes the transaction to affect no batch and fail closed; it must not allocate first and validate later. The current live lease/fence checks are the available guard, but do not yet claim this complete database CAS/uniqueness behavior.
- `created_at` and `updated_at` are audit/age fields only, not persisted ordering inputs. Selection uses ADR 0065's exact within-entity tuple `(priority, due_tick_id, entity_enqueue_seq, source_kind, commandId_or_effectKey)` plus persisted rotating/deficit scheduler state and declared cost for cross-entity fairness. If a legacy source exposes only a timestamp, the scheduler must normalize it once into the persisted due point plus deterministic entity sequence and source identity before selection; it must not sort directly by wall-clock insertion time.
- Recovery that observes multiple durable rows for the same coordinates treats the region as inconsistent, pauses it, and requires reconcile tooling before normal ticks resume.
- Current live boundary note: gameplay commands do not yet use the fuller target-state `BOUND_TO_BATCH` vocabulary. Instead, the command ledger exposes `enqueueSeq`, `STAGED`, `DRAINED`, and later terminal/requeue outcomes, while the sealed batch manifest digest is used to ensure replay reuses only matching staged batches instead of silently mutating an older batch contract.
- **Target-state authority:** The durable `tick_batch` record, `tick_effects` ledger, and immutable sealed execution context are authoritative for selected work, execution identity, and replay. The sealed context includes the complete batch coordinates, executor fence, selected-work manifest/digest (including each item's declared phase/lane/cost-class and persisted ordering inputs), effect identities, and pinned execution inputs/version/script patch. Redis `pending`/event streams plus external metrics, logs, traces, and audit streams are projections or diagnostics; they cannot prove a commit or justify replay on their own.
- For the complete expected concrete participant-projection set linked to a root `EffectId` and participant guard contract, every expected `(root_effect_id, typed_operation, tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch, tick_id, target_aggregate_type, target_aggregate_id)` projection must eventually have **exactly one terminal state**. `typed_operation` is part of the participant projection alongside the root and exact target aggregate; `effect_key` remains descriptor metadata for correlation and lookup, not a substitute for that identity. Replay/recovery must reconcile the whole expected set and fail closed for missing, extra, partial, or conflicting projections; one root-effect or one ledger-row result is insufficient:
  - `status = APPLIED` – effect successfully committed to domain state, or durable replay evidence proves that it was already committed under the existing fenced verification policy.
  - `status = ABANDONED` – effect intentionally skipped or judged unrecoverable only when durable evidence proves it was unapplied and the existing recovery policy permits terminalization; inconclusive work remains non-terminal reconciliation work.
- A duplicate handler attempt may return a replay/no-op outcome such as `replay_ok`, but that outcome is recorded in service metrics/audit and never as a third ledger status; when the effect is already reflected in durable state, its ledger row is `APPLIED`.
- Rows must not remain in `SCHEDULED` beyond the emitted replay-convergence budget without escalation; stuck rows are treated as operational smells and surfaced via metrics and alerts. Any explicitly inconclusive or reconciliation-required work—including current-epoch timeout or retry-exhaustion cases—remains non-terminal while the required evidence/attestation completes. No timeout, health signal, or exhausted retry budget may be inferred as `ABANDONED` without durable evidence proving the effect was unapplied.
- Retention cleanup cannot substitute for recovery disposition. Game Session must not age-delete a command receipt, batch/effect lineage, selected-work manifest, or participant guard while any supported producer retry, replay, restore, or reconciliation path can still reference or resurrect the logical work. Cleanup follows the owning family's terminal/reference predicate and safe watermark; non-terminal, inconsistent, quarantined, or inconclusive rows remain blockers regardless of age. This is the recovery consequence of the canonical service-owned contract in [ADR 0163](./decisions/adr-0163-service-owned-retention-classes-with-cross-service-safety.md); duration selection and cleanup operations remain owned by the [Scaling Runbook](./system-architecture-scaling-runbook.md#data-retention-and-high-churn-tables).

#### Replay Convergence Budget (Normative)

To keep replay-controller alerting and runbooks deterministic, the replay path uses an explicit convergence budget:

- `tick_effects_replay_convergence_budget_seconds{scope_class}` is the canonical emitted budget for each active region-sized gameplay scope.
- Bootstrap alert only (not a production acceptance formula):
  - `replay_convergence_budget_seconds = max(60, ceil(20 * tick_interval_ms / 1000))`
  - The numeric `60s` floor remains provisional until measured backlog, scan/claim, throughput, owner latency, and fault-injection evidence establishes the environment budget.
- Prometheus recording rules should also expose:
  - `tick_effects_pending_oldest_age_seconds{scope_class}` = `time() - tick_effects_pending_oldest_scheduled_timestamp_seconds`
  - `tick_effects_replay_slo_breached{scope_class}` when oldest pending age exceeds the emitted convergence budget
  - `tick_effects_replay_starved{scope_class}` when `tick_effects_pending_total > 0` but replay batches do not advance for longer than the emitted convergence budget
- Alerting guidance:
  - Warning/P1 when `tick_effects_replay_slo_breached` is sustained beyond one budget window for an otherwise running region.
  - Escalate the region to `DEGRADED` or `STALLED` and require scoped remediation when the oldest pending age exceeds multiple budget windows or when `tick_effects_replay_starved` remains true.
  - The emitted budget is evidence-derived from admitted/recovery backlog distributions, durable scan/claim latency, fair worker throughput, owner response/error rates, and representative fault-injection capacity. `max(60 seconds, 20 ticks)` is only a provisional bootstrap alert, not a production acceptance guarantee; environment overlays must publish the accepted numeric budget rather than hiding it inside PromQL.

Canonical alert names for shared rulesets:

- `TickEffectsReplaySloBreached`
  - Fires when `tick_effects_replay_slo_breached` is sustained and the region has exceeded its emitted convergence budget.
- `TickEffectsReplayStarved`
  - Fires when `tick_effects_replay_starved` is sustained, indicating the replay controller is not servicing a region with pending work.

Environment overlays may tune durations or routing, but they should preserve these alert names and the shared labels (`service`, `severity`, `owner`, `runbook`) so Logging & Admin and incident runbooks remain stable.

#### Ownership Summary

Tick-related durable structures are intentionally split between a central ledger and per-service guards:

- **Game Session Service**
  - Owns the global tick effect ledger tables (for example `tick_effects`) and any cross-region follow-up tables that encode scheduled work between regions.
  - Defines the concrete ledger projection of the root `EffectId` and its typed participant-guard contract, and is responsible for converging `(rootEffectId, typedOperation, tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, targetAggregateType, targetAggregateId)`—with `effectKey` retained as descriptor metadata and linked to that root, operation, and guard evidence—to `APPLIED` or `ABANDONED` when the recovery policy permits terminalization.
- **Domain services (Entity Management, World Management, etc.)**
  - Own their own idempotency guard tables (for example `entity_tick_state`, `tick_effect_guard`) in their respective schemas.
  - Use those guards to implement per-aggregate `last_tick_id` and operation-level idempotency patterns, but do not introduce additional “mini-ledgers” for tick effects.

New designs must not create ad-hoc ledger tables for tick effects in other services; they should either extend the Game Session–owned ledger/follow-up schema or add domain-local guard tables that project the existing `EffectId`.

Cross-region follow-ups extend the same concrete identity rather than treating a region or scope label as sufficient. Each durable coordinator, target follow-up, and origin-addressed result must carry both the origin and target runtime tuples `(tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch)` (with the origin/target role made explicit). `playable_state_namespace_id` participates in target-leg uniqueness/claim identity, exact replay comparison, old-epoch recovery, and coordinator-to-result correlation; `playable_state_scope` is separately persisted and exact-validated evidence, not a replacement key. A target result is admissible only when its origin and target namespace/timeline tuples, stable follow-up/coordinator identity, target effect/aggregate identity, and immutable request/result digest agree with the durable rows. Recovery retains the original namespace-complete projection and must not rebind an old follow-up to a new namespace or epoch. The current cross-region storage/wire substrate remains narrower than this target contract.

Replay of a tick is driven from ledger state:

- When reprocessing a tick, the executor loads ledger rows for that `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId>` with `status = SCHEDULED` and:
  - A `SCHEDULED` effect may be re-queued/re-run only after durable authoritative domain evidence proves that it was unapplied and that replay is safe under the existing evidence policy. Absence of `APPLIED` evidence, a missing response, or an unreadable/ambiguous guard is inconclusive: retain `SCHEDULED`/reconciliation-required, escalate, and do not re-queue. After a permitted replay, mark the row `APPLIED` only when durable domain evidence and the fenced replay policy prove application.
  - Marks effects `ABANDONED` with a precise reason only when replay proves no required mutation succeeded and the effect is no longer valid (expired session, entity gone, descheduled tick, and so on). Inconclusive work remains non-terminal reconciliation work under its original root `EffectId` and is escalated for reconciliation.
  - When idempotency evidence may prove that an effect was already applied, replay verification reconciles the complete expected concrete participant-projection set and binds the evidence to the `SCHEDULED -> APPLIED` transitions in fenced/CAS transactions (conceptual shape):

    ```sql
    BEGIN;
    -- This is the target-state sealed-context CAS. RegionStatus
    -- supplies the region-scoped authority; current-live does not
    -- enter this branch and uses the separate CAS described below.
    SELECT o.region_epoch, o.executor_fence
    FROM region_status AS o
    WHERE o.tenant_id = :tenantId
      AND o.game_instance_id = :gameInstanceId
      AND o.region_id = :regionId
    FOR UPDATE;
    -- Require exactly one current RegionStatus row and exact equality
    -- with :regionEpoch and :executorFence before touching any ledger row.
    -- Read and lock the batch as a consistency check, not as authority:
    SELECT b.tick_batch_id, b.region_epoch, b.executor_fence,
           b.sealed_execution_context_digest, b.sealed_execution_context_ref
    FROM tick_batch AS b
    WHERE b.tick_batch_id = :tickBatchId
    FOR UPDATE;
    -- Require exactly one immutable binding: either
    -- (sealed_execution_context_digest IS NOT NULL AND
    --  sealed_execution_context_ref IS NULL), or the inverse. Both null or
    -- both present is invalid and must ROLLBACK/fail closed.
    -- If the batch stores sealed_execution_context_ref, resolve its
    -- Game-Session-owned immutable record in this same transaction:
    SELECT sc.context_digest
    FROM sealed_execution_context AS sc
    WHERE sc.context_ref = :sealedExecutionContextRef
    FOR SHARE;
    -- The reference lookup must return exactly one row. Zero or multiple
    -- rows, or a digest mismatch, must ROLLBACK/fail closed. Use the direct
    -- field or this resolved digest as sealed_context_digest; never rebuild
    -- context from mutable state.
    -- expected is the sealed set of concrete participant projections for this
    -- effect: root EffectId + typed operation + exact target aggregate,
    -- including every required participant and its immutable request-digest
    -- binding. Read all expected guard rows and durable domain evidence under
    -- the current fence/context. Require set equality: every expected
    -- projection is present exactly once, no extra projection exists, and no
    -- projection is missing, partial, or conflicting.
    -- Partition that complete set before the CAS into:
    --   expected_scheduled: exact expected ledger rows still SCHEDULED;
    --   expected_terminal: exact expected rows already terminal.
    -- Separately verify every expected_terminal projection against its durable
    -- terminal evidence. APPLIED is valid replay evidence; ABANDONED remains
    -- governed by the existing evidence-qualified terminalization policy.
    -- CAS only expected_scheduled rows and persist their matching evidence
    -- digest/reference in the same owner transaction.
    UPDATE tick_effects AS e
    SET status = 'APPLIED',
        replay_verification_evidence_digest = p.evidence_digest,
        replay_verification_recorded_at = :now
    FROM expected_scheduled AS p
    JOIN tick_batch AS b ON b.tick_batch_id = p.tick_batch_id
    LEFT JOIN sealed_execution_context AS sc
      ON sc.context_ref = b.sealed_execution_context_ref
    JOIN region_status AS o
      ON o.tenant_id = b.tenant_id
     AND o.game_instance_id = b.game_instance_id
     AND o.region_id = b.region_id
    WHERE e.tick_batch_id = p.tick_batch_id
      AND e.concrete_projection = p.concrete_projection
      AND e.status = 'SCHEDULED'
      AND o.region_epoch = :regionEpoch
      AND o.executor_fence = :executorFence
      AND b.region_epoch = o.region_epoch
      AND b.executor_fence = o.executor_fence
      AND (
        (b.sealed_execution_context_digest IS NOT NULL
         AND b.sealed_execution_context_ref IS NULL
         AND b.sealed_execution_context_digest = :contextDigest)
        OR
        (b.sealed_execution_context_digest IS NULL
         AND b.sealed_execution_context_ref IS NOT NULL
         AND sc.context_digest = :contextDigest)
      );
    -- Require the affected-row set to equal expected_scheduled only, and
    -- separately require expected_terminal to equal the already-terminal
    -- expected projections with valid evidence; otherwise ROLLBACK and fail
    -- closed/reject/retry. If expected_scheduled is empty, commit an
    -- idempotent replay success only when every expected projection is valid
    -- APPLIED evidence, rather than treating zero affected rows as success.
    -- This target-state RegionStatus authority join is one Game Session
    -- transaction; the current-live manifest CAS is a separately selected
    -- branch.
    COMMIT;
    ```

    The complete-set evidence check, target authority epoch/fence check, sealed context-digest check, all required Game Session ledger CAS operations, and replay-verification metadata commit together within this one Game Session durable transaction. A stale authority or batch fence/context, missing/extra/partial/conflicting projection, or concurrent winner affects an incomplete set and must roll back and fail closed/reject or retry; an audit/log write without this CAS is not proof and must not be used to skip the domain call. Domain-service guards and evidence remain in their own service-local transactions; this flow does not imply a cross-service database transaction. Current-live uses a separate CAS over the current `RuntimeOwnershipStatus` authority, exact `regionEpoch`/opaque `executorFence`, and the complete selected-work manifest plus `manifest_digest` against durable source, ledger, and participant evidence; sealed execution context is not required at that current-live boundary. Both branches fail closed on mismatches and leave durable state unchanged.
- Every staging script must carry an immutable collision-safe pending envelope for each staged member. `effect_key` remains descriptor metadata only. The envelope must carry the exact `tick_batch_id` plus either the complete root `EffectId`/participant projection (`root_effect_id`, canonical `typed_operation`, exact `target_aggregate_type`/`target_aggregate_id`, and the exact scope, epoch, and tick) or an equivalent immutable exact ledger-row identity that resolves to that complete projection. Stage, commit, rollback, and cleanup preserve the envelope unchanged; recovery exact-set-compares pending envelopes with the durable batch/ledger expected set and fails closed for missing, extra, duplicate, or conflicting envelopes. An `effect_key`-only, count-only, or digest-only correlation is insufficient, and malformed or orphan pending state cannot be committed.
- Recovery rules for Redis/SQL mismatch are explicit:
  - durable tick-batch + `SCHEDULED` ledger rows exist, Redis `pending` missing:
    - replay proceeds from PostgreSQL using the durable batch manifest and `SCHEDULED` ledger rows; it does not rely on re-materializing the old tick through the normal hot-path staging scripts.
    - First implementation treats normal tick Lua scripts as hot-path guards only. Recovery drives effects directly to `APPLIED`/`ABANDONED` only where the evidence policy permits, leaves any inconclusive work non-terminal under its original root `EffectId`, escalates it for reconciliation, reconciles any surviving source claims/entries against the manifest, and then clears stale coordination residue.
  - Redis `pending` exists without a durable tick-batch:
    - treat the Redis entry as orphaned coordination state, clear it, alert, and do not commit work from it.
  - durable tick-batch and Redis `pending` do not produce exact set equality for the immutable pending envelopes and durable batch/ledger expected set (including missing, extra, duplicate, malformed, or conflicting members; count/digest disagreement is only a diagnostic signal):
    - mark the batch inconsistent, pause the region, and require reconcile tooling before resuming normal ticks.

### Command Record Convergence Under Replay and Reset

Command recovery must converge just like effect recovery. The canonical command lifecycle, authoritative status surface, terminal outcomes, durable storage rule, and terminal mapping table are owned by [Tick Execution Flows: Command Outcome Status Surface](./system-architecture-tick-execution-flows.md#command-outcome-status-surface-required).

The command status is one durable authoritative, versioned record keyed by `(tenantId, gameInstanceId, commandId)`, whether the physical storage is one row or an ingress row plus an outcome projection. Recovery reconciles that record from the complete durable effect/ledger evidence under the owner-defined status-version CAS; it must not recompute a disposable view from Redis, events, or logs. Every transition preserves the canonical `failureCode`/`failureMessage` pair when a terminal reason applies, and an identical retry returns the prior versioned outcome while a conflicting transition fails closed.

Recovery-specific behavior is:

- Reset or coordination-loss reconciliation under [ADR 0058](./decisions/adr-0058-class-specific-redis-loss-outcomes.md) may terminalize `LOST_BEFORE_STAGING` only for an `ACCEPTED_VOLATILE` command still in `RECEIVED` or `ENQUEUED` and not durably tied to a surviving `tick_batch_id`. `BOUND_TO_BATCH` and `TERMINAL` records are excluded. Checking for a surviving `tick_batch_id` and terminalizing `LOST_BEFORE_STAGING` must be one atomic owner-defined CAS/version-fenced operation on the authoritative command record: if concurrent staging wins, its `BOUND_TO_BATCH` transition wins and the loss transition affects zero rows; only the no-batch CAS winner may terminalize the command. `ACCEPTED_DURABLE` remains governed by its feature-specific durable intake and safe-replay/re-drive contract, even before batch binding, and is never classified as `LOST_BEFORE_STAGING` by this reset rule.
- Commands in `BOUND_TO_BATCH` follow the batch/effect replay path; command status remains distinct from effect-ledger status while the owner-defined mapping is applied.
- Recovery, reset, and purge retain the structured terminal reason on the authoritative command record. A nonblank operator purge reason remains the command's failure message rather than existing only in logs or audit metadata.
- Command reconciliation runs in the same operational scope as ledger replay/reset tooling, so clients can retry the same `commandId` for status lookup without leaving a pre-staging dedupe record indefinitely non-terminal. The lookup must include the `{tenantId, gameInstanceId}` scope and authorize the caller against the command's bound subject/actor identity or an explicitly authorized internal/operator authority; `commandId` alone is insufficient. The canonical lifecycle and status contract is [Tick Execution Flows: Command Outcome Status Surface](./system-architecture-tick-execution-flows.md#command-outcome-status-surface-required).

### EffectId, Ledger Rows, and Guard Keys

The canonical root `EffectId` described in the identifier glossary and `system-architecture-transactions.md` ties together the logical effect, Game Session participant status, and replay lineage. Participant guard uniqueness is deterministically derived from exactly that root effect, typed operation, and target aggregate; the immutable request digest is bound to the guard identity, while durable outcome/evidence state is mutable guard-row state protected by CAS. The root identity, original participant ledger projection, and collision-safe guard identity are preserved for every ordinary retry or uncertain replay. Retry eligibility/attempt metadata (such as retry count, next eligible tick, and source-claim state) is separate and must not rewrite the original `regionEpoch`, `tickId`, `effectKey`, or root/typed-operation/target projection. A future linked-attempt or supersession model requires an explicit contract; only a **post-abandon re-drive**—after the original effect is conclusively terminal `ABANDONED` and its source claim is terminalized—may receive a fresh logical `EffectId`, a later tick coordinate, a new retry/source identity, and durable lineage to the original identities.

- Tick coordination in Redis.
- Tick effect ledger rows in PostgreSQL.
- Per-aggregate and operation-level idempotency guards in domain schemas.

In schema terms:

- The tick effect ledger persists a concrete, namespace-complete projection of the root `EffectId` and its tick coordinates for scope, ordering, evidence, and target lookup:
  - The storage projection includes at minimum `(root_effect_id, typed_operation, tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch, tick_id, target_aggregate_type, target_aggregate_id)`. `playable_state_scope` and the tick/region coordinates are persisted and exact-validated evidence or ordering metadata, not uniqueness dimensions. The ledger's primary or unique key is derived only from `(root_effect_id, typed_operation, target_aggregate_type, target_aggregate_id)`, plus any owner partition the canonical owner-local schema requires to make that identity collision-safe; it must not promote `playable_state_scope` or tick coordinates into guard uniqueness. `effect_key` may be stored in the projection for stable descriptor/correlation metadata, but it is not the root identity or sole collision protection. Mutable `status`, `reason`, `outcome`, evidence/reconciliation fields, and timestamps remain outside the uniqueness key. The physical schema may encode target identity separately, but it must retain an explicit collision-safe projection containing `root_effect_id`, `typed_operation`, and the exact target aggregate.
  - Additional columns such as `command_id`, `automation_dispatch_id`, or `command_ordinal` may exist for queryability and scripting correlation, but they do not replace the root `EffectId` or participant guard identity.
- Guard tables such as `tick_effect_guard` implement the deterministic participant projection for multi-effect operations:
  - Their uniqueness identity binds root `EffectId`, typed operation, and target aggregate. The immutable request digest is bound and compared on every replay; durable outcome/evidence/reconciliation fields are mutable row state protected by CAS and are not uniqueness inputs.
  - The concrete tick/ledger projection columns remain storage scope/order/lookup metadata, not an alternative guard identity. Handlers must not invent alternative idempotency keys for tick-driven effects.

The ledger makes replay visible operationally via metrics such as:

- `tick_effects_pending_total{scope_class}`
- `tick_effects_applied_total{scope_class}`
- `tick_effects_abandoned_total{scope_class,reason}`
- `tick_effects_replayed_total{scope_class}` (or, where available, `tick_effect_outcome_total{outcome="replay_ok"}` for service-level detail)
- `tick_effects_pending_oldest_scheduled_timestamp_seconds{scope_class}` – helper metric tracking the oldest `created_at` among SCHEDULED rows for each approved bounded gameplay scope; `created_at` is diagnostic age input only, never an ordering key.
- `tick_effects_pending_oldest_age_seconds{scope_class}` – recording rule for the current age of the oldest `SCHEDULED` row.
- `tick_effects_replay_convergence_budget_seconds{scope_class}` – emitted budget for how long replay may take before the scope is considered unhealthy.
- `tick_effects_replay_slo_breached{scope_class}` – recording rule indicating oldest pending age has exceeded the emitted budget.
- `tick_effects_replay_starved{scope_class}` – recording rule indicating replay batches are not advancing despite pending work.
- `tick_durable_commit_total{scope_class}` – count of ticks that reached the durable commit boundary.
- `tick_coordination_cleared_total{scope_class}` – count of ticks whose Redis coordination state reached the in-flight clearance boundary.
- `tick_cleanup_lag_ms{scope_class}` – lag from durable commit to coordination-cleared for each tick.

Alerts fire when:

- Pending (`SCHEDULED`) counts remain above thresholds for longer than a tick window, or
- The abandoned ratio grows unexpectedly for a region or effect type, or
- The oldest SCHEDULED effect in a region exceeds the emitted replay budget as indicated by `tick_effects_pending_oldest_age_seconds` and `tick_effects_replay_slo_breached`.

These metrics complement the Redis- and lock-level health metrics described in the Redis architecture and operations docs.

Operators diagnosing stalled regions, replay storms, or ledger backlogs should use these metrics in combination with the Tick Health & Ledger dashboard exported under `design/observability/grafana/tick-health-ledger.json` and the incident flows described in `system-architecture-tick-incident-runbook.md`.

Replay fairness is part of the operational contract, not just an implementation detail:

- Dashboards and alerts should show regions where `tick_effects_pending_total > 0` but `tick_effects_replay_batches_total` is not increasing over the same interval.
- Sustained `tick_effects_replay_scan_lag_ms` growth for a subset of regions should be treated as replay-controller starvation, even if a hot region is still making progress.

### Ledger Replay Controller

Responsibility for driving ledger rows to a terminal outcome lies with the Game Session Service:

- A background “ledger replay controller” in Game Session:
  - Periodically scans for `SCHEDULED` rows that have exceeded the emitted replay-convergence budget for a given `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId)`.
  - Replays eligible effects using the same idempotent handlers the tick pipeline uses, marking rows `APPLIED` only when durable domain evidence confirms success.
  - Marks rows `ABANDONED` with a precise reason only when replay evidence proves the effect was not applied and is no longer safe or meaningful (for example, entities removed, sessions expired, or region/tenant/cluster resets that bumped `regionEpoch`); inconclusive work remains non-terminal under the original root `EffectId`, is escalated for reconciliation, and follows the applicable evidence policy.
  - Enforces bounded fairness across active scopes so replay does not starve smaller tenants/regions behind one hot backlog:
    - Scans and replays in bounded batches per `<tenantId, gameInstanceId, regionId>`.
    - Uses round-robin (or weighted-fair) scheduling across regions rather than draining one region completely before touching others.
    - Emits `tick_effects_replay_scan_lag_ms{scope_class}` and `tick_effects_replay_batches_total{scope_class}` so starvation is visible without reintroducing raw tenant/game-instance/region labels to Prometheus.
- The controller also runs on service startup for each region to converge any lingering `SCHEDULED` rows before normal tick processing resumes; any inconclusive row instead remains reconciliation-required, is escalated, and cannot be hidden by normal startup replay.
- For incident handling, the same replay logic is exposed via coordination tooling (for example, an admin CLI or maintenance API) so operators can explicitly drive convergence for a selected `(tenantId, gameInstanceId, regionId, regionEpoch)` when guided by runbooks in the Redis operations docs.
- Convergence SLO contract (required):
  - `SCHEDULED` rows should converge to terminal `APPLIED`/`ABANDONED` within the emitted `tick_effects_replay_convergence_budget_seconds` window when evidence permits; explicitly marked inconclusive/reconciliation-required work—including current-epoch timeout or retry-exhaustion cases—remains non-terminal and is escalated under the applicable reconciliation policy.
  - If oldest `SCHEDULED` age exceeds the emitted budget for a region, the region is escalated to `DEGRADED`/`STALLED` and incident runbooks require scoped remediation.

### Inconclusive Old-Epoch Reconciliation Policy

Before an affected region, tenant, or cluster scope reopens, recovery completely enumerates durable old-epoch `SCHEDULED` effects, their linked batch/manifest source claims, and follow-ups independently of Redis hints. Source claims for commands, timers, retries, and remote follow-ups remain part of the gate even when their source entry is no longer present; each claim must receive the owning lifecycle's conclusive terminal disposition together with its linked effect/batch evidence. Each conclusively evidenced row is terminalized as `APPLIED` or `ABANDONED` with an explicit reset/topology reason; an inconclusive effect, follow-up, batch, or source claim remains fenced and reconciliation-required and blocks reopen until an authority-fenced terminal decision exists. No old row, claim, or manifest is rewritten, rebound, adopted into the new epoch, or omitted from the enumeration. If the intent remains semantically required, only an explicitly authorized current-epoch request with fresh identity, current-state revalidation, and lineage may be created after the old identity and source claim are terminal.

Across every epoch, an effect whose application status is inconclusive—including a current-epoch timeout or retry-exhaustion case—remains `SCHEDULED`/reconciliation-required and is escalated. Absence of a response, a deadline, control-plane health, or exhausted retry budget never proves `ABANDONED`; only durable evidence proving that no required mutation succeeded may authorize that terminal outcome. The policy below adds the authority-fenced attestation required for old-epoch reset work.

The same policy applies whenever a region-, tenant-, or cluster-scoped reset or epoch fence leaves an old-epoch effect in `SCHEDULED` and normal domain inspection cannot yet prove whether the effect was applied. Recovery must use a bounded, out-of-band attestation under the original `EffectId`; reset scope alone is never evidence for terminalization. The attestation:

- retains the original concrete ledger/guard projection `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)`, its root `EffectId`, and the maintenance/recovery fence that authorized the reset;
- reads the authoritative domain guard/state and any existing `APPLIED` reflection without invoking current-epoch staging or replay;
- records an auditable attestation outcome and retries only within the emitted replay-convergence budget; and
- permits exactly these outcomes: `APPLIED` when durable domain state or the authoritative guard proves the effect occurred; `ABANDONED` with an explicit reset/reconciliation reason only when durable state proves it was unapplied and cannot be safely re-driven; or an explicit reconciliation-required non-terminal state while the evidence remains inconclusive.

If the bounded attestation remains inconclusive, the original effect remains non-terminal under an explicit reconciliation-required marker. It is not an `ABANDONED` outcome, is never sent through normal current-epoch replay, and blocks the affected reset scope from declaring convergence until an authority-fenced worker records a terminal decision supported by authoritative evidence. A later epoch must not reuse the old row as though it were a new current-epoch effect.

Current-epoch executors must never re-drive the old `EffectId`. Any feature that needs to carry work across the epoch boundary requires separately designed maintenance or saga/outbox tooling. A new current-epoch identity is a **post-abandon re-drive** only: the old effect and source claim must first be terminal `ABANDONED`, then the tooling allocates a later coordinate, fresh root and retry/source identity, and durable lineage. Ordinary retry/replay preserves the old root, and inconclusive work cannot be re-driven.

Common scenarios and invariants:

- **Primary crash, AOF fully up to date**
  - Redis: `pending`, locks, timers, and retry metadata preserved for recent ticks.
  - PostgreSQL: all committed effects durably stored.
  - Invariants: no double-apply; at-most-one executor per region after lease re-acquisition.
  - Action: new executor replays any surviving `pending` entries; ticks complete or are retried automatically.
- **Crash during the measured Redis unreplicated-write window**
  - Redis: some recent `pending`/lock/queue keys for the last ticks may be missing.
  - PostgreSQL: effects applied before the crash remain; very recent, in-flight effects may or may not have been applied.
  - Invariants: apply the ADR 0058 class-specific outcome for each affected work item. Durable commands, staged effects/retries, and correctness-bearing timers are replayed, reconstructed, reconciled, or evidence-terminalized; only class-declared lossy hints may be dropped. No correctness-bearing work is silently treated as lost.
  - Action:
    - Metrics and dashboards surface gaps or stuck regions.
    - Operators treat a breached unreplicated-write SLO as a trigger to run the **ledger replay controller** (and, where appropriate, the scoped reset/reconcile flows) for affected `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch)` combinations.
    - The controller applies ADR 0058 class-specific outcomes: it drives eligible durable effects and correctness-bearing timers through evidence-backed replay or terminalization, while session/lease/cache/wake-up loss remains a latency or reconnect consequence. Any inconclusive effect remains reconciliation-required and escalated; old-epoch effects additionally follow the [Inconclusive Old-Epoch Reconciliation Policy](#inconclusive-old-epoch-reconciliation-policy). The controller does **not** re-stage older ticks through normal tick-staging Lua scripts; cross-epoch reconstruction uses the explicit fresh-identity lineage path.
    - The same reconcile scope also converges accepted-but-unbound `ACCEPTED_VOLATILE` command records according to the [canonical command outcome contract](./system-architecture-tick-execution-flows.md#command-outcome-status-surface-required), so ingress dedupe state does not strand commands indefinitely after coordination loss. `ACCEPTED_DURABLE` records delegate to their feature-specific durable intake and safe-replay recovery contract.
- **GC pause > `lock_ttl_ms` but < `lease_ttl_ms`**
  - Redis: locks may expire and be reacquired; `pending` remains; lease still held by original executor.
  - PostgreSQL: any effects applied before the pause remain consistent; replays of the same concrete ledger/guard projection `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` retain the original root `EffectId` and are treated as no-ops by idempotent handlers.
  - Invariants: no double-apply; lease ownership unchanged; at-most-one executor per region.
  - Action: late work that fails token checks is retried; regions with persistent over-TTL behavior may be marked degraded until configuration or workload is adjusted.
- **GC pause > `lease_ttl_ms` (lease lost)**
  - Redis: lease may move to a new executor; `pending` and queues are preserved subject to the AOF window.
  - PostgreSQL: effects applied by the old executor before losing the lease remain consistent; the new executor replays the same effects with idempotent handlers.
  - Invariants: no double-apply; Redis lease liveness plus the durable `executor_fence` prevents an old executor from remaining an authorized writer.
  - Action: work the old executor attempts to perform after lease loss is discarded; the new executor drives recovery; the region may be degraded until behavior stabilizes.
- **Redis coordination cluster outage**
  - Redis: coordination keys temporarily unavailable; a tail window of recent `pending`/lock/queue state may be lost depending on failure and AOF configuration.
  - PostgreSQL: remains authoritative; effects committed before the outage are not rolled back.
  - Invariants: apply the ADR 0058 class-specific outcome. Durable commands/effects/correctness-bearing timers are replayed, reconstructed, reconciled, or evidence-terminalized from their durable records; only class-declared lossy hints may be dropped, while already-applied effects are not undone.
  - Action: Game Session halts ticks/commands for affected regions, following the Redis outage policy; after Redis recovers, the recovery subsystem and operators decide whether to skip, retry, or repair missing ticks.

These scenarios assume the Redis persistence/profile contract described in `system-architecture-redis.md`. If AOF or replication settings differ, the same ADR 0058 class-specific outcomes and ledger replay rules still apply, but the measured coordination-exposure evidence and affected recovery scope may change.

## Stalled Regions and Downstream Behavior

Stalled regions are those that still hold `tick-executor-lease:{tenantRegionTag}` but have not made forward progress (for example, no successful commits or too many consecutive failures) for several multiples of `tick_interval_ms`, as defined in the concepts doc. Once a region is classified as stalled:

- The scheduler stops scheduling new ticks for that `<tenantId, gameInstanceId, regionId>` until progress recovers or operators intervene.
- New gameplay commands targeting that region are rejected with a clear “region unavailable”–style error instead of being accepted and queued behind a non-advancing executor.
- The existing executor continues renewing the lease for a short grace period so no other instance takes over and attempts the same failing work against unhealthy dependencies.
- If a large number of regions on the same instance become stalled, that instance’s readiness/liveness may be marked unhealthy so orchestration can restart it once downstream services (for example PostgreSQL, other gRPC services) are healthy again.

Lease expiry and failover remain about coordination safety (avoiding split-brain on Redis state). Stalled-region handling layers a progress watchdog on top: leases describe who owns a region’s coordination keys, while stalled/healthy state describes whether that owner is actually advancing game state.

## Stuck Pending Entries and Recovery

In rare cases, a `tick:{tenantRegionTag}:pending` entry may remain present even though repeated replays cannot complete successfully (for example, due to a persistent domain bug). A small recovery subsystem handles these **stuck ticks**:

- A background watcher scans metrics and/or a compact Redis/PostgreSQL index of `pending` entries to identify candidates, such as:
  - `pending` keys that have existed across multiple tick intervals with exhausted retries.
  - Regions where `tick:{tenantRegionTag}:pending` has not advanced despite repeated recovery attempts.
- Candidate stuck ticks are enqueued into a `tick_recovery` queue or table with metadata such as `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, executorFence, firstSeenAt, lastRetryAt>`. The namespace identifier must be carried by the candidate or resolved from an authoritative, namespace-bound runtime record before any shadow-state lookup, cleanup, replay, or mutation; an unbound or ambiguous resolution fails closed. Metrics and Redis contents identify candidates only; they do not establish the authoritative region, namespace, epoch, or owner.
- An automated recovery worker:
  - Re-reads the current control-plane/runtime-health authority for the complete candidate scope and, before any recovery or cleanup mutation, revalidates the same in-memory Redis lease token it acquired and the expected candidate `regionEpoch`, then separately verifies the current durable `executorFence`. A missing, stale, or mismatched token, epoch, or authority record fails closed; the worker must not persist the raw token, clear pending state, or stage/commit work under a different owner or epoch.
  - Uses the same idempotent handlers and ledger/guard patterns as normal ticks to drive any effects associated with the stuck tick to terminal `APPLIED` or `ABANDONED` outcomes only when the existing evidence policy permits; inconclusive effects remain non-terminal reconciliation work and are escalated. It reconciles any higher-level tick or command status (for example `FAILED`/`SKIPPED`) from that effect evidence into the durable authoritative status record, using its monotonic status version/CAS and preserving `failureCode`/`failureMessage`; the status record is not merely a derived view, and events/metrics remain projections of it.
  - Clears `tick:{tenantRegionTag}:pending` and associated retry metadata via a dedicated, idempotent helper path.
  - Emits detailed logs and metrics for audit and dashboards.
- Operator tooling allows manual override for complex cases (for example, suspected data corruption), with two typical modes:
  - **Recommendation mode** – the system proposes recoveries; operators approve or override.
  - **Auto-recovery mode** – low-risk patterns are resolved automatically once thresholds are met.

Retry and timer queues are protected against unbounded growth:

- Retry queues (`retry:{tenantRegionTag}`) are ZSETs keyed by `next_eligible_tick_id` (target region timeline tick IDs). Scripts accept the scheduler’s current `(regionEpoch, tickId)` context, process at most `N` entries per invocation, and enforce a maximum retry budget per action.
- Timer keys (`timer:{tenantRegionTag}`) are ZSETs keyed by `due_ms` (absolute wall-clock milliseconds); scripts accept `now_ms` as a caller-supplied `ARGV` value (never Redis `TIME`), pop at most `N` timers per call, and delete processed members.
- Defensive limits (for example, maximum timers per region) trigger alerts or throttling if exceeded so bugs cannot create unbounded timer or retry growth.

Entity Management provides the reference example for per-aggregate tick idempotency; see [Entity Management Operations](./microservices/entity-management-service/operations.md#tick-idempotency).

## Domain Idempotency Rules (Timeline Context + Owner Guards)

Domain services use the **region-scoped tick timeline** `(regionEpoch, tickId)` as ordering and fence context, not as the canonical idempotency token for tick-driven effects. The canonical participant guard identity is `(rootEffectId, typedOperation, exact target aggregate)`; the immutable request digest is bound to that identity and any owner-local partition needed for collision safety. A guard must not substitute the timeline tuple for that root/operation/aggregate identity.

`tickId` is monotonic only **within a given `regionEpoch`** and may restart at `0` after a region-scoped or tenant-scoped reset that bumps `regionEpoch`. Any idempotency scheme that keys only on `tickId` (without `regionEpoch`) is therefore unsafe across resets.

Two patterns are used:

- **Per-aggregate last-tick state**
  - This pattern is allowed only for aggregates that are provably updated at most once per tick within a region epoch.
  - It is a narrow exception, not the default for gameplay-visible mutations.
  - Typical safe uses are once-per-tick watermark-style updates or aggregates whose design guarantees a single logical writer/effect per tick.
  - Aggregates that may receive multiple legitimate effects in one tick must not use this pattern.
  - A shadow tick-state record such as `entity_tick_state` is keyed by `(tenant_id, game_instance_id, playable_state_namespace_id, region_id, target_aggregate_type, aggregate_id)`, not by the aggregate identifier alone; `playable_state_scope` is separately persisted and exact-validated evidence, not a key dimension.
  - The shadow state stores at minimum:
    - `last_region_epoch`
    - `last_tick_id`
    - (plus tenant/game-instance/region identifiers or a foreign key implying them)
  - When applying a tick effect:
    - The handler resolves and reads the shadow tick-state row using `(tenant_id, game_instance_id, playable_state_namespace_id, region_id, target_aggregate_type, aggregate_id)`, exact-validating separately persisted `playable_state_scope` evidence; an aggregate-only, aggregate-type-free, or namespace-free lookup is invalid.
    - If `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)` for that exact row, the update is treated as a replay or out-of-order attempt and becomes a no-op (or, in strict modes, a validation-only check).
    - If `(last_region_epoch, last_tick_id) < (currentRegionEpoch, currentTickId)`, the handler applies the change and updates `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` on that same composite-key row in the same transaction as the domain mutation.
- **Operation-level effect guard**
  - This is the default pattern for gameplay-visible mutations.
  - Operations that may touch multiple aggregates or legitimately apply multiple distinct effects to the same aggregate in a single tick (for example trades, combat damage, healing, AoE damage, room occupancy changes, drops/pickups, or multi-target buffs) use a small guard table such as `tick_effect_guard` with a concrete participant projection storing:
    - The uniqueness identity is the root `EffectId`, typed operation, and exact target aggregate. The immutable request digest is bound to that identity and checked on replay; the durable outcome is mutable guard-row state advanced by CAS. The concrete fields below are retained as the participant projection for scope, ordering, and target lookup and must not be mistaken for an alternative uniqueness identity.
    - `root_effect_id`
    - `typed_operation`
    - `tenant_id`
    - `game_instance_id`
    - `playable_state_namespace_id`
    - `playable_state_scope`
    - `region_id`
    - `region_epoch`
    - `tick_id`
    - `effect_key` – a deterministic identifier describing the logical effect (for example `entity:<entityId>:award:achievement:<achievementId>` or `room:<roomId>:drop:item:<itemId>`).
    - `target_aggregate_type`
    - `target_aggregate_id`
  - Inside the same transaction as the domain update, the handler attempts to insert a participant guard whose uniqueness identity is exactly `(root EffectId, typed operation, target aggregate type, target aggregate ID)`, binding the immutable request digest and storing the namespace-complete concrete projection `(root_effect_id, typed_operation, tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id)` as scope/order/ledger metadata. Replay and exact-set comparisons must compare the namespace as well as the scope; an implementation may omit a separate projection column only when the root/target guard is an authoritative, exact namespace-bound proof that is enforced by the owner transaction:
    - If the insert succeeds, the effect is new for this participant identity and the handler applies all associated state changes, advancing mutable outcome state by CAS as required.
    - If the uniqueness identity conflicts, the handler compares the stored namespace, typed operation, exact target aggregate, and immutable request digest. An exact existing guard replay returns its stored outcome without reapplying domain state; any changed namespace, operation, target, or digest fails closed. It must also verify the complete expected participant-guard set and corresponding authoritative state. Only a complete, consistent set is a replay/no-op; a partial conflict is incomplete and must reconcile the original root `EffectId` or fail closed.
  - A replay at a later coordinate that retains the same root `EffectId`, typed operation, namespace, and target aggregate therefore reuses/conflicts with the same guard; `region_epoch`, `tick_id`, and `effect_key` metadata cannot create a second participant guard.

Examples:

- **Once-per-tick aggregate watermark (per-aggregate last-tick state)**
  - `AdvanceRegionAuraWatermark` receives `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, targetAggregateType, targetAggregateId)`.
  - The design guarantees this aggregate is advanced at most once per tick.
  - It reads the shadow tick state for the complete `(tenantId, gameInstanceId, playableStateNamespaceId, regionId, targetAggregateType, targetAggregateId)` key, exact-validating separately persisted `playableStateScope` evidence, and applies the update only when `(last_region_epoch, last_tick_id) < (regionEpoch, tickId)`.
  - If `(last_region_epoch, last_tick_id) >= (regionEpoch, tickId)`, the handler treats the request as a replay/out-of-order and returns without changing state.
- **Trade between two entities (operation-level effect guard)**
  - The `TradeItem` endpoint explicitly receives `rootEffectId` and immutable `requestDigest` in addition to `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, fromEntityId, toEntityId, itemId)`. It creates one participant guard/ledger projection linked to that root effect per affected inventory aggregate.
  - The typed operation is `TradeItem`; it computes `effectKey = "trade:" + fromEntityId + ":" + toEntityId + ":" + itemId` only as stored projection/ledger metadata, not as guard uniqueness identity.
  - In one transaction it:
    - Attempts to insert one guard per affected inventory aggregate with uniqueness identity `(rootEffectId, typed operation=TradeItem, targetAggregateType=INVENTORY, targetAggregateId)`, binding and comparing `requestDigest` and retaining `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, effectKey)` as namespace-complete projection/ledger metadata.
    - If any uniqueness identity conflicts, it re-reads the complete expected guard set and the authoritative state of both source and target inventories. Only a complete guard set whose root effect, typed operation, target aggregates, immutable `requestDigest`, and inventory state all match the committed transfer is a replay/no-op; a partial conflict, missing guard, or mismatch must reconcile the original root effect or fail closed. `effectKey` is never used to admit a replay.
    - If the insert succeeds, it debits the item from `fromEntityId`, credits it to `toEntityId`, and commits both inventory changes and the guard-row insert together.

Operationally:

- Every tick-driven write path must use either the per-aggregate `last_tick_id` pattern or the operation-level guard pattern.
- The default review assumption is that a gameplay-visible mutation requires an effect guard unless the design explicitly proves “at most one logical effect per aggregate per tick”.
- Domain handlers treat Redis locks and leases as opaque; they never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` directly.
- Operations that cannot be made idempotent or compensatable at the domain layer—for example payments, emails, or webhooks into third-party systems—must not be executed directly inside tick-driven handlers. Those flows must use the saga/outbox patterns in `system-architecture-transactions.md` so they can tolerate retries and partial failures independently of tick replay.

### Effect Identity, Endpoint Semantics, and Outcome Metrics

Tick-driven domain calls use the stable root `EffectId` plus a shared participant guard contract for retries:

- Game Session assigns the stable root `EffectId` for the logical effect. A concrete ledger/guard/storage projection must carry the namespace-complete identity and tick context `(rootEffectId, typedOperation, tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` for scope, ordering, and exact target lookup, unless the owner transaction enforces an equivalent namespace-bound root/target proof. It remains linked to the root identity. Each participant derives its guard uniqueness identity from the root `EffectId`, typed operation, and exact target aggregate, binding the immutable request digest; durable outcome/evidence state is mutable guard-row state protected by CAS. Additional operation-specific distinctions belong in that typed operation/target/request-digest contract; they must not create a competing root identity.
- Game Session passes the same opaque root `EffectId` plus the structured operation, target, request digest, and any concrete ledger projection fields to each tick-invoked handler. Handlers validate and project that contract into their own guards; they must not generate fresh random IDs, re-derive from mutable payload, or silently drop required scope or target fields.

Every gRPC endpoint that can be invoked from tick execution must document and implement:

- **Duplicate handling** – repeated calls with the same effect identity must return OK / “already applied” semantics (for example, no new HP or inventory changes) instead of logical errors that drive infinite retries.
- **Already-in-desired-state handling** – if the target state already reflects the intended outcome, the endpoint first resolves the participant guard in the owner transaction. An exact existing guard with the same root `EffectId`, typed operation, exact target aggregate, and immutable `requestDigest` returns its stored outcome; when no matching guard exists, the owner records a new durable guarded no-op under the current root `EffectId`, typed operation, exact target, and `requestDigest` before returning OK. Mutable state already matching the requested value is not replay evidence by itself, and a conflicting or ambiguous guard fails closed.
- **Retry classification** – errors must be classified as retryable (transient infrastructure issues) vs terminal (invalid inputs, missing aggregates). Terminal errors may move the corresponding ledger/guard entry into a terminal state (for example `ABANDONED`) only when the error and durable evidence prove that no required mutation succeeded; inconclusive work remains reconciliation-required rather than entering a retry loop or being bulk-terminalized.

Endpoints participating in tick-driven effects should also emit a small, standardized metric:

- `tick_effect_outcome_total{service, effect_type, outcome}`
  - `service` – owning microservice (for example `entity-management-service`).
  - `effect_type` – low-cardinality side-effect category (for example `entity_state`, `inventory`, `quest`, `room_state`), **not** the full effect identity.
  - `outcome` – `first_apply`, `replay_ok`, or `guard_error` (for unexpected failures at the idempotency boundary).

This metric provides a cross-service view of how often replay paths are exercised and highlights handlers that are not honoring the canonical idempotency contract.

All tick metrics use bounded labels only. `scope_class` is a controlled enum such as `region`, `game_instance`, `tenant`, or `cluster`; it is not a serialized scope or an identifier. `service`, `effect_type`, `outcome`, and `reason` are also controlled vocabularies. Exact `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId>` diagnosis comes from the durable tick-batch/ledger and runtime control-plane records, joined by `tick_batch_id`, `executor_fence`, `EffectId`, or an approved trace/correlation ID. Prometheus labels must never carry those raw tuple values.

### Side-Effect Categories and Idempotency Strategies

Different side-effect categories may use different persistence primitives, but each must be explicitly tied back to tick-driven effect identity:

- **Award-once side effects** (items, currency, XP):
  - Use a durable “effect applied” ledger keyed by the effect identity (or a unique equivalent) with insert-if-absent semantics.
  - On uniqueness conflict, treat the call as an idempotent replay and return OK without applying additional changes.
- **Monotonic state changes** (achievements, unlocks):
  - Use monotonic fields or unique constraints such as `(tenantId, playerId, achievementId)` plus a mapping to effect identity when they trigger additional rewards or notifications.
- **Notifications and events**:
  - Use transactional outbox entries keyed by effect identity (often `eventId == effectId`) so producers deduplicate sends; consumers may still apply defensive deduplication.
- **Versioned aggregates and CAS-style updates**:
  - Compare-and-set/version checks must treat replay-stale writes as OK/no-op outcomes instead of “conflict” errors that cause unbounded retries.

Together with the tick effect ledger, these patterns ensure that Redis coordination and replay logic remain simple while each domain service guarantees that its persistent mutations and outward side effects are safe to attempt multiple times.

## Tick-Aware Reset Scenarios

Coordination resets are expressed in terms of Redis scopes (region, tenant, cluster) in `system-architecture-redis-reset-and-recovery.md`. From the tick system’s perspective, they also have **timeline and ledger effects**:

- **Region-scoped reset**
  - Timeline impact:
    - For the affected `<tenantId, gameInstanceId, regionId>`, the documented region-scoped coordination keys for the current `regionEpoch` are dropped according to the reset policy matrix: `tick:{tenantRegionTag}:meta`, `tick:{tenantRegionTag}:pending`, `tick:{tenantRegionTag}:queue:<entityId>`, `tick:{tenantRegionTag}:lock:<entityId>`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}`.
    - Gameplay session records such as `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` follow the explicitly recorded region reset policy in [Redis Reset & Recovery](./system-architecture-redis-reset-and-recovery.md); `sessionctx:*` pre-auth context is always invalidated or rebuilt and is never preserved. Account-issued token registry records and issuer-generation projections under `session:auth:token:*` and `session:auth:generation:*` are not deleted by a region-only prefix scan. If the recorded policy preserves gameplay sessions, they must still pass auth/revocation validation and any required re-authentication before rebind.
    - Region-authoritative `tick:{tenantRegionTag}:session-binding:<entityId>` keys are still region-scoped and are dropped as the narrow session-to-region bridge family; preserved sessions must be rebound through that bridge before normal command intake resumes. No broad `tick:{tenantRegionTag}:*` scan is implied.
    - A new `regionEpoch` is established; subsequent ticks for that region advance on the **new (bumped) `regionEpoch`** starting at `tickId=0` on the coordination timeline described in `system-architecture-redis.md`.
  - Ledger behavior:
    - Tick effect ledger rows with `status = SCHEDULED` for the affected `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` must be reconciled or explicitly marked for the authority-fenced old-epoch policy; they must not remain silently pending or be hidden by a bulk terminalization.
    - The scoped ledger reconcile inspects each row's durable domain guard/state and any existing `APPLIED` ledger or domain-state reflection before choosing a terminal outcome. Effects already reflected in durable state are marked `APPLIED`; effects whose domain state confirms they were not applied and cannot be safely re-driven across the reset are marked `ABANDONED` with a reset reason such as `RESET_REGION_SCOPED`.
    - The reset step must not bulk-mark every `SCHEDULED` row `ABANDONED`; an inconclusive row follows the [Inconclusive Old-Epoch Reconciliation Policy](#inconclusive-old-epoch-reconciliation-policy) under its original EffectId and is never sent through normal current-epoch replay.
    - Only features that explicitly document alternative behavior may opt into a post-abandon re-drive under the new epoch, and such behavior must be implemented via dedicated reset tooling after terminal `ABANDONED`/source-claim disposition, with a later coordinate, fresh root/source identity, and durable lineage; it is not ad-hoc replay of a `SCHEDULED` row.
  - Player impact:
    - In-flight actions follow ADR 0058 class-specific outcomes; correctness-bearing work is durably reconstructed or explicitly terminalized only where authoritative evidence permits, while inconclusive work remains fenced/reconciliation-required and lossy hints may be delayed or dropped.
    - Players may observe explicit non-application, delay, replay, or evidence-qualified abandonment around the reset boundary; game UX must not describe ambiguous loss as silent success.

- **Tenant-scoped reset**
  - Timeline impact:
    - A tenant reset is an orchestration over every running or recoverable `gameInstanceId` belonging to the tenant, not a single tenant-only key scan or epoch. The reset first snapshots the authoritative game-instance inventory and mapping generation under the reset lease, then enumerates every `<gameInstanceId, regionId>` pair and reads each region's current `regionEpoch` and executor fence.
    - For every enumerated game instance and every one of its regions, the region-scoped coordination keys are cleared and that instance-region's `regionEpoch` is bumped independently. A changed mapping generation, missing instance/region, lost lease, or fence mismatch fails closed before the next mutation; it must not reset only the regions visible in Redis.
    - Cross-region flows (for example follow-ups) resume only under the new epochs; stale follow-ups from previous epochs are ignored or reconciled.
    - Gameplay session records follow the canonical reset-policy matrix in [Redis Reset & Recovery](./system-architecture-redis-reset-and-recovery.md), while `sessionctx:*` pre-auth context is always invalidated or rebuilt and is never preserved. A tenant reset does not perform account-wide token invalidation. Preserved gameplay sessions must rebind after auth validation.
  - Ledger behavior:
    - Tick effect ledger rows for each enumerated instance-region scope with `status = SCHEDULED` follow the [Inconclusive Old-Epoch Reconciliation Policy](#inconclusive-old-epoch-reconciliation-policy): inspect durable domain state and any existing `APPLIED` reflection first, mark confirmed reflections `APPLIED`, and mark `ABANDONED` with a tenant-scoped reset reason such as `RESET_TENANT_SCOPED` only when domain state confirms the effect was unapplied and cannot be safely re-driven across the reset.
    - Re-scheduling across epochs is allowed only for features that explicitly document this requirement and provide dedicated tooling.
  - Player impact:
    - The tenant experiences a “clean slate” for tick coordination: timers, retries, and queued commands are cleared.
    - Long-lived domain state (characters, inventory, world) is preserved; tick-driven features must be designed so players can naturally continue from durable state.

- **Cluster-scoped reset**
  - Timeline impact:
    - All `<tenantId, gameInstanceId, regionId>` pairs on the deployment lose region-scoped tick coordination keys and receive new epochs.
    - This is effectively a deliberate, unbounded coordination-loss event for all regions and must be treated as a rare, planned operation.
  - Ledger behavior:
    - `SCHEDULED` ledger rows for all affected regions follow the [Inconclusive Old-Epoch Reconciliation Policy](#inconclusive-old-epoch-reconciliation-policy): durable domain state or an existing `APPLIED` reflection wins, and the ledger reconcile tooling may use a cluster reset reason such as `RESET_CLUSTER_SCOPED` only for effects confirmed unapplied and not safely re-drivable across the reset.
    - Only in exceptional, explicitly designed cases should migration or batch re-drive tooling attempt to carry work across a cluster reset; such tools must document their expectations and failure modes in the owning feature’s design docs.
  - Shared replay/token behavior:
    - Account-issued token registry records and issuer-generation projections are canonical preserved state for region- and tenant-scoped resets; neither scope invalidates them through coordination cleanup.
    - A cluster reset may invalidate the shared Gateway replay domain and Account-owned token records only after protected admission is closed and the documented replay-continuity quarantine plus Account repair/reset cutover has completed. The cutover advances the issuer generation; token cleanup then removes old records, and the current issuer-generation projection is rebuilt and proven before protected traffic reopens.
  - Player impact:
    - All active regions experience at least a brief pause while epochs are re-established and ticks resume under the new coordination state.
    - UX and communications for planned cluster resets should set expectations (maintenance windows, possible brief rollbacks).

In all three cases, the **goal is convergence**:

- For each complete expected concrete participant-projection set linked to a root `EffectId`, every expected `(rootEffectId, typedOperation, tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, targetAggregateType, targetAggregateId)` projection must eventually have exactly one terminal ledger state (`APPLIED` or `ABANDONED`) when the evidence policy permits terminalization. `typedOperation` is part of the expected set alongside the root and exact target aggregate; `effectKey` remains descriptor metadata for correlation and lookup. Reconciliation rejects missing, extra, partial, or conflicting projections; any inconclusive row remains reconciliation-required and non-terminal, with old-epoch rows additionally following the [Inconclusive Old-Epoch Reconciliation Policy](#inconclusive-old-epoch-reconciliation-policy), including across resets.
- Reset tooling and Game Session control flows must ensure that no tick remains forever “half-applied” in the ledger (for example, perpetually `SCHEDULED` with no chance of replay), by running a per-effect tick-effect-ledger reconcile for the relevant `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch)` combinations as part of the reset flow. The reconcile must inspect durable domain state before terminalizing each row and may abandon only effects confirmed unapplied.

These expectations should be reflected in the coordination reset tooling described in `system-architecture-redis-operations.md` and the reset policy matrix in `system-architecture-redis-reset-and-recovery.md`.

## Cross-Region Failure Semantics (Conceptual)

Cross-region tick-driven flows (such as combat actions that affect entities in multiple regions) are designed to be **best-effort and eventually consistent** by default rather than globally atomic across regions:

- Each leg of a cross-region flow (origin-region effects, target-region effects, and any results relayed back) uses its own `EffectId` and converges independently to `APPLIED` or `ABANDONED` in the tick effect ledger only when the existing evidence policy permits terminalization; inconclusive work remains non-terminal reconciliation work under its original root `EffectId` and is escalated, with old-epoch work additionally following the applicable reset policy.
- Origin regions derive a high-level **command outcome** (for example `SUCCESS`, `PARTIAL`, `FAILED`) from the combination of leg outcomes and timeouts only under the command family’s ADR 0053 declaration. `PARTIAL` is valid only for a permitted terminal subset declared before execution; unresolved required work remains `PENDING`, and a timeout or multi-leg failure does not silently become an undeclared `PARTIAL`.
- Features that truly require “all-or-nothing across regions” semantics must build that coordination explicitly on top of the tick primitives (for example using saga/outbox patterns outside the tick loop as described in `system-architecture-transactions.md`); it is not provided implicitly by the tick engine.

When origin reconciliation accepts a target result that drives a dependent child effect (for example, remote lifesteal damage driving a local heal), the acceptance transition is crash-safe: it cannot become durable without a deterministic child-effect intent. The accepted result and child intent commit in one owner-local transaction when possible, or a resumable durable acceptance intent records both before acceptance is acknowledged. Acceptance replay reuses the child identity and exact accepted-result digest; missing, extra, partial, or conflicting result/intent projections fail closed. This is a durability consequence of the existing coordinator lifecycle, not a new outcome.

Operationally:

- Result admission and timeout arbitration are serialized in one origin coordinator transaction and lock domain. A result durably admitted before arbitration wins and is evaluated before timeout; if timeout wins, the origin terminal outcome is immutable. A late result is recorded separately and cannot reopen or rewrite that outcome. The operational reason and any unresolved uncertainty remain durable coordinator evidence.
- If the origin scope is canonical `PAUSED` or `STALLED`, gameplay tick deadlines do not advance. A separate operational maximum-real-wait policy may terminalize stranded coordination, but it must record that operational reason and must not claim that origin tick time advanced.
- Explicit, evidence-backed `ABANDONED` outcomes from a target region (for example, entity no longer valid, region reset, or unrecoverable domain error) are treated the same way at the origin.
- Late remote results are handled by the required lifecycle in `system-architecture-tick-execution-flows.md`:
  - Once origin has reached timeout-abandoned terminal state, late replies are either explicitly ignored (`LATE_RESULT_IGNORED`) or reconciled by a documented feature-specific compensation flow (`LATE_RESULT_RECONCILED`), never silently merged.
- Compensation beyond simple local corrections (for example refunding currency, undoing non-idempotent external side effects) must be orchestrated via saga/outbox flows outside the tick loop, not by attempting cross-region rollback inside tick execution.

### Cross-Region Follow-Ups and Region-Epoch Changes

Region resets and epoch bumps intentionally sever the old coordination timeline for a `<tenantId, gameInstanceId, regionId>`. Cross-region follow-ups must behave predictably across those boundaries:

- Follow-up rows retain a specific target timeline and persisted `due_tick_id` eligibility coordinate, while their stable target ledger/guard projection is `(tenant_id, target_game_instance_id, target_playable_state_namespace_id, target_playable_state_scope, target_region_id, target_region_epoch, target_effect_id, typed_operation, target_aggregate_type, target_aggregate_id)` plus their origin coordinator/effect identity. They **never** silently migrate to a new epoch. `due_tick_id` is owner-CAS ordering state, not stable uniqueness or guard authority; `effect_key` remains descriptor/correlation metadata only and is never uniqueness or guard authority.
- When a target region’s `regionEpoch` is bumped (via region/tenant/cluster reset):
  - Recovery first completely enumerates every undrained follow-up and `SCHEDULED` ledger row for the old epoch from durable storage. Conclusive rows terminalize as `APPLIED` or `ABANDONED` with a reset/topology reason under the authority-fenced policy; an inconclusive row keeps the scope fenced and cannot be hidden by Redis loss or omitted from the enumeration.
  - The ledger replay controller applies the [Inconclusive Old-Epoch Reconciliation Policy](#inconclusive-old-epoch-reconciliation-policy): it marks confirmed reflections `APPLIED`, converges only effects confirmed unapplied to `ABANDONED` with a reset-specific reason (for example `RESET_REGION_SCOPED` or `RESET_TENANT_SCOPED`), and keeps inconclusive effects non-terminal under their original EffectId for authority-fenced reconciliation rather than attempting to “replay them into the new epoch”. Normal current-epoch replay is forbidden.
  - Target-region tick executors ignore old-epoch work based on epoch/tick guards in their coordination scripts; they only stage and apply effects for the current epoch.
- Origin regions:
  - Observe evidence-backed `ABANDONED` outcomes for remote legs and compute the appropriate high-level command outcome only when the command family declared that terminal combination before execution; unresolved required work, including timeouts without authoritative non-application evidence, remains `PENDING`/reconciliation-required rather than becoming `PARTIAL`.
  - Surface player-facing feedback consistent with that outcome (for example “your cross-region trade failed due to region reset; your currency has been refunded”).

Designs that genuinely need to carry cross-region work across region resets or epoch changes must be treated as **exceptional** and implemented using out-of-band saga/outbox workflows with their own reset/runbook stories, not as normal tick behavior.

### Cross-Region Follow-Up Record Contract (Required)

Cross-region follow-ups are durable PostgreSQL records owned by Game Session (or another explicitly designated tick coordinator) that represent “work created in one region but owned by entities in another”. To keep cross-region correctness independent of best-effort Redis hints, follow-up records must satisfy a minimal, explicit contract. A result-return follow-up carries its origin and coordinator correlation durably; the target must never infer origin identity from its own target scope.

- **Identity and scoping**
  - Each follow-up is tied to a specific target region timeline and effect identity, including at minimum:
    - `coordinator_id` (the durable origin-owned coordinator identity), `origin_command_id`, `origin_game_instance_id`, `origin_playable_state_namespace_id`, `origin_playable_state_scope`, `origin_region_id`, `origin_region_epoch`, and `origin_tick_id`;
    - `tenant_id`, `target_game_instance_id`, `target_playable_state_namespace_id`, resolved `target_playable_state_scope`, `target_region_id`, `target_region_epoch`
    - `due_tick_id` in the target region timeline (preferred; do not use wall-clock due-time fields for cross-region follow-up eligibility)
    - `origin_effect_id` for the originating leg and a distinct `target_effect_id` for the target leg, plus the target-leg `typed_operation`, `effect_key` (stable descriptor/correlation metadata only), `target_aggregate_type`, and `target_aggregate_id`.
    - An immutable `request_digest` and sealed required-participant manifest (or immutable manifest reference plus digest) are persisted as binding/comparison evidence. Target admission and replay compare the exact operation, target, digest, and manifest binding; any missing, conflicting, or mismatched value fails closed.
    - exact target identity plus required feature preconditions for ownership, location, and aggregate version. Target execution requires those preconditions to remain valid under current authoritative state and executor fence; matching `target_region_epoch` and `due_tick_id` alone are insufficient.
  - `due_tick_id` is computed from the authoritative region status read, not from Redis hint keys. Current live uses `GetRuntimeOwnershipStatus` and its committed region/epoch/tick fields; target state uses `GetRegionTickStatus` / `RegionStatus.lastCommittedTickId`. The target owner compare-and-set persists and exact-compares this coordinate with the target epoch when admitting/claiming the follow-up; retries and replays reuse it, but it is not part of stable follow-up uniqueness:
    - Canonical baseline: `due_tick_id = target_last_committed_tick_id + delta_ticks` (for immediate eligibility, `delta_ticks = 1`).
    - The writer must read the target region epoch and committed tick from one authoritative status response and persist `target_region_epoch` and `due_tick_id` together from that same read so eligibility is deterministic across retries and failover.
- **Uniqueness / de-duplication**
  - The follow-up table must prevent duplicate scheduling of the same logical follow-up for the same origin coordinator, target timeline, target effect, typed operation, and exact target aggregate (for example via a unique key that includes `(coordinator_id, origin_effect_id, target_effect_id, typed_operation, tenant_id, target_game_instance_id, target_playable_state_namespace_id, target_region_id, target_region_epoch, target_aggregate_type, target_aggregate_id)` or an equivalent projection that matches the feature’s semantics). `target_playable_state_scope` remains separately persisted and exact-validated target-scope evidence, not a uniqueness-key dimension. `due_tick_id` remains the owner-CAS eligibility/ordering coordinate but is not a stable uniqueness or guard input; `effect_key` is descriptor/correlation metadata only and is not a uniqueness or guard input.
- **Claiming and concurrency**
  - Draining follow-ups into a tick must use database-side concurrency control (for example `FOR UPDATE SKIP LOCKED` or an atomic “claim” update) so that only one executor can claim a follow-up at a time, even during failover or when multiple workers are racing around lease changes.
- **Epoch boundaries**
  - Follow-ups must never silently “carry over” to a new epoch:
    - When `target_region_epoch` changes, durable recovery enumerates and reconciles every old-epoch follow-up before reopen: proven application becomes `APPLIED`, proven non-application may become `ABANDONED`, and inconclusive work remains fenced/non-terminal and blocks reopen until the authority-fenced decision completes. Explicit maintenance re-scheduling into the new epoch is a post-abandon re-drive only after the old effect and source claim are terminal, with a later coordinate, fresh root/source identity, and durable lineage.
- **Result return**
  - A target result row or inbox entry repeats `coordinator_id`, `origin_command_id`, `origin_game_instance_id`, `origin_playable_state_namespace_id`, `origin_playable_state_scope`, `origin_region_id`, `origin_region_epoch`, `origin_tick_id`, and the complete target follow-up identity (`target_effect_id`, typed operation, target game instance, target playable-state namespace, target_playable_state_scope/timeline, exact target aggregate, immutable request digest, and sealed-manifest binding) before recording the target terminal outcome.
  - Origin-side reconciliation claims that row idempotently and advances the coordinator only after the correlation matches the durable coordinator record. A transient message, target-only `gameInstanceId`, or mutable command payload is not sufficient correlation.
- **Topology changes**
  - Mapping-changing split/merge operations must bump `regionEpoch` for affected source/target regions before follow-up draining resumes (see required topology protocol in `system-architecture-ticks.md`).
  - **Target-state rollover invariant:** If a topology change needs a new target leg, the old follow-up identity and the new identity must be handled atomically: one durable transaction creates and links the new target record, then marks the old identity `ABANDONED` only after that new record is durable and the existing per-effect evidence policy permits terminalization. If the records cannot share a transaction, persist a fenced durable rollover intent containing both identities, the desired mapping, and the sealed follow-up context; recovery retries that intent until the new record and link are durable before terminalizing the old identity, while inconclusive old-epoch work remains non-terminal under its original root `EffectId`.
  - **Current drift:** The current cross-region follow-up path has durable rows and epoch/mapping checks but does not yet claim this atomic or recoverable rollover boundary. Until it is implemented and proven, topology tooling must remain paused/fenced and must not mark an old identity `ABANDONED` merely because its mapping changed.
  - If region split/merge changes which region owns the target entity, topology-change tooling must either:
    - Complete the atomic or durable-intent rollover above, creating a new target-leg/follow-up identity for the new `(target_game_instance_id, target_region_id, target_region_epoch)` before marking the old identity `ABANDONED` with a topology-change reason only where the existing per-effect evidence policy permits. The new record must carry a fresh `target_effect_id`, the typed operation, its sealed manifest and immutable request digest, and a new `due_tick_id`, linked to the old identity by durable correlation; updating only the target instance, region, or epoch on the old record is forbidden.
    - Mark it `ABANDONED` with a topology-change reason only when the existing evidence policy proves replaying it under the new mapping is not valid and no required mutation succeeded; otherwise retain the old identity as non-terminal reconciliation work under its original root `EffectId`.

## Remote Hint Markers and Resets

Cross-region flows may use best-effort Redis hint markers such as `remote:{tenantInstanceTag}:<entityId>` (for target entities, with the tag derived from target `<tenantId, gameInstanceId>`) to reduce latency when draining remote follow-ups. Operationally:

- These markers are **latency hints only**:
  - They may be overwritten, duplicated, or lost.
  - Correctness is derived from durable follow-up rows in PostgreSQL, not from the presence of `remote:*` keys.
- The marker key must be TTL-bounded so the hint keyspace cannot grow without bound:
  - Canonical write form: `SET remote:{tenantInstanceTag}:<entityId> 1 PX remote_hint_ttl_ms` with default `remote_hint_ttl_ms = 60_000`.
  - TTL refresh happens when new durable follow-ups are recorded for that target entity (and optionally while backlog remains due); expiry is treated as normal and must not be interpreted as “no work exists”.
- Region-level coordination resets do not attempt to delete `remote:*` keys because these keys are instance-scoped rather than region-scoped.
- Tenant- and cluster-scoped coordination resets may drop `remote:*` keys alongside other coordination state for the affected tenant; losing them remains safe because they only affect how quickly remote follow-ups are noticed, not whether those follow-ups eventually apply.
- After a region reset, the next tick executor:
  - Resumes draining due follow-ups from PostgreSQL into its normal tick pipeline.
  - Treats any stale or missing `remote:*` markers as affecting only how quickly it notices new work, not whether the work is eventually applied.

When debugging cross-region issues, operators should rely on PostgreSQL follow-up tables, tick effect ledgers, and the metrics described in the execution-flow docs rather than assuming `remote:*` keys are authoritative.

## Testing Tick Idempotency and Redis Replays

Because crash recovery relies on idempotent handlers, each service with tick-driven logic should include integration tests that simulate Redis-style replays:

- Invoke the same handler multiple times with the same root `EffectId`, typed operation, target, immutable request digest, concrete ledger/guard projection `(rootEffectId, typedOperation, tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)`, and payload, and assert that:
  - The first call mutates state as expected.
  - Subsequent calls are treated as replays and do not apply additional logical effects (HP changes, inventory moves, etc.).
- Repeat the same `effectKey` against a different `targetAggregateType` or `targetAggregateId` and assert that the distinct target is not incorrectly deduplicated. For script-generated fan-out effects, vary `automationDispatchId` and, as applicable, the root `EffectId`, typed operation, and target aggregate to create distinct logical identities; `effectKey` is projection/storage metadata, never guard uniqueness. A request-digest mismatch for the same guard identity must fail closed.
- Exercise both idempotency strategies:
  - Per-aggregate `last_tick_id` tables (for single-entity updates).
  - Operation-level `tick_effect_guard` tables (for multi-entity effects).
- Where practical, share a small test harness that:
  - Constructs synthetic `tick:{tenantRegionTag}:pending` payloads.
  - Drives the same sequence of domain calls multiple times, mimicking replay of a pending tick after a crash.
  - Verifies that final PostgreSQL state is identical regardless of how many times the tick is “reapplied”.
- Include crash-window tests for the two-boundary model:
  - Crash before durable commit: verify replay convergence and no double-apply.
  - Crash after durable commit but before coordination cleanup: verify no watermark regression, no duplicate effects, and cleanup completion before next tick staging.

CI pipelines should run these replay tests; changes to tick handlers that break idempotency ought to fail tests before reaching production.

## Design Checklist for New Tick-Driven Commands

When introducing a new command type that will run under tick control, design docs and code reviews should explicitly cover:

- **Is this command tick-driven?**
  - Does it run because an entry is dequeued from `tick:{tenantRegionTag}:queue:<entityId>` or because a tick timer/retry fired?
  - If not, it may follow different idempotency rules and does not belong in this section.
- **What is the idempotency key?**
  - For single-aggregate updates: which complete `(tenant_id, game_instance_id, playable_state_namespace_id, region_id, target_aggregate_type, aggregate_id)` key, separately validated `playable_state_scope` evidence, and `(last_region_epoch, last_tick_id)` (or equivalent) fields and table enforce “at most one update per tick timeline” for that aggregate?
  - For multi-aggregate or multi-effect operations: what participant guard identity combines the root `EffectId`, typed operation, and target aggregate, and how is the immutable request digest bound and checked? `effect_key` is a concrete storage projection/descriptor, not a replacement for that guard identity.
- **Where is the guard persisted?**
  - Which schema/table holds the per-aggregate “last applied tick” state or `tick_effect_guard` entries?
  - Is there a primary key or unique index that enforces the idempotency key at the database level?
- **What happens on replay?**
  - What does the handler do when it detects that the guard already exists or `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)`?
  - Is the “replay” outcome clearly documented and tested (no new logical effects, optional consistency verification)?
- **Are there any non-idempotent external effects?**
  - If the handler sends email, charges a payment method, or calls an external API with irreversible effects, how is that separated from the tick-driven part (for example, via an outbox entry processed by a saga)?

Pull requests that add new tick-driven commands should link back to this checklist and show how each item is satisfied before the feature is considered complete.
