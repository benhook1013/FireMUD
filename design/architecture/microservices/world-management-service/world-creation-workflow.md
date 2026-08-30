# World Creation Workflow

World creation is a long-running process that prepares the initial world state for a new **game instance** using already-published world data for a given `tenantId`. The workflow now runs on the shared **Temporal** substrate so prepare/activate/fail/terminate orchestration survives service restarts, keeps deterministic workflow identity, and exposes operator-visible execution status without pulling gameplay runtime onto the workflow engine. [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md) owns lifecycle authority and all-owner cleanup convergence; this document records the World workflow consequence. [ADR 0122](../../decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) owns stable playable identity and replacement classification. World Management's activation lifecycle surface is invoked when the platform provisions a new game instance for an existing tenant, typically from the Game Session Service. The identifiers involved are:

- `tenantId` – identifies the game (tenant) as described in
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- `versionId` – identifies the published world/template data to use, as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `generationConfigRevision` – the frozen generation-config identity recorded at publish for the target `(tenantId, versionId)`; activation must fail if this identity cannot be resolved.
- `gameInstanceId` – identifies the specific running world instance recorded in
  the Game Session Service.
- `playableStateNamespaceId` – identifies durable playable state independently of the replaceable runtime instance. World retains the resolved namespace across replacement and uses the instance id only for topology and other explicitly instance-scoped state.

The cross-platform workflow placement and adopter-proof requirements are owned by [Transaction Strategies](../../system-architecture-transactions.md#mandatory-workflow-adopter-classification) and [Temporal Control-Plane Workflows](../../system-architecture-temporal-workflows.md). This document records the World Management consequences: the target `world-lifecycle` workflow coordinates the local lifecycle commands and read surface, while World Management's durable lifecycle row and epoch remain authoritative, with durable launch/workflow identity plus shared digest-bound step identity (stable step name, deterministic occurrence, execution role, and immutable request digest; see [ADR 0078](../../decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md)). Gameplay ticks and live in-world mutations remain outside this workflow. Current lifecycle proof covers the extracted command path and Temporal-backed status surface, while full durable step-guard and restart/failure proof remains an implementation gap noted below.

## Implementation Status

The current first implementation cut exposes only the synchronous initial lifecycle/termination seam through `TerminateWorldInstance(tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId)`, with Game Session reading fresh lifecycle state through `GetWorldInstanceLifecycle` immediately before termination. It accepts `ACTIVE -> TERMINATING`, invokes the current Entity cleanup seam, deletes the runtime `world_event`, `room_instance_exit`, `room_instance`, `zone_instance`, and `region_instance` subset, and then saves `TERMINATED` while retaining terminal lifecycle evidence. It does not yet implement the target frozen required-owner snapshot and ownership-registry revision, per-owner durable acknowledgements, complete registered World-owned cleanup, or final all-owner lifecycle CAS. A `TERMINATED` fast path returns before comparing `terminationRequestId`, so a mismatched terminal retry is incorrectly reported as success rather than an idempotency conflict; exact terminal identity comparison and focused retry/conflict proof remain required. The current lifecycle tests prove this synchronous cleanup path only; they do not prove `TERMINATING` retention across owner failure/retry, frozen-snapshot acknowledgement, final fenced CAS, or matching-versus-mismatched terminal retry outcomes. This current seam is not proof of the target cross-service termination workflow.

World creation consumes a previously resolved immutable launch descriptor for the attempt. At minimum this descriptor carries:

- `launchDescriptorId`
- `controlPlaneRequestId`
- `tenantId`
- `gameTemplateId` when launch originated from a template
- resolved `versionId`
- resolved `scriptPatchVersion` (or explicit null)
- `expectedVersionStateEpoch`
- `expectedGenerationConfigRevision`
- any approved `remapSetId`
- resolved runtime flags/defaults needed by downstream services

For replacement-instance launches, World Management now persists the frozen `remapSetId` on `world_instance` so later cutover/termination consumers can prove they are still operating on the same approved cross-version remap identity that launch resolution selected.

Game Design owns the immutable mapping from `<tenantId, gameTemplateId, controlPlaneRequestId>` to `launchDescriptorId`. After validating that descriptor, the Game Session instance-creation orchestrator owns the durable mapping from the same launch attempt and `launchDescriptorId` to one `gameInstanceId`; an exact retry reuses both mappings, while a conflicting descriptor, template, or instance fails closed. World workflow execution consumes those identities under the distinct business scope `<tenantId, gameInstanceId, controlPlaneRequestId>`, while each lifecycle step uses the shared digest-bound workflow/step guards and its deterministic occurrence and role. `controlPlaneRequestId` is the sole launch/workflow request identity; neither a separate world-creation request ID, a Temporal run/attempt identity, nor a generated instance may replace it.

The target workflow uses the published world topology for the chosen `tenantId` and `version_id`, inserts a starter region instance, and can generate terrain chunks and materialize instance-scoped population schedules for expansive worlds. Any initial event scheduling remains subject to the owning typed effect contract; while the Weather aggregate selector is unresolved, initial Weather scheduling is explicitly deferred and non-mutating. Activation-time topology must be derived only from the attested published template graph plus runtime generation runs whose canonical inputs are covered by the same published `generationConfigRevision`. Throughout the workflow, the Temporal workflow and its activities:

- Reads only **template/topology** rows keyed by `(tenantId, versionId)` (for example `region_template`, `zone_template`, `room_template`, or authored generation metadata); and
- Writes only **instance** rows keyed by `(tenantId, gameInstanceId)` (for example `region_instance`, `zone_instance`, `room_instance`, `world_event`) plus optional **instance-scoped population schedules/materializations** derived from already-published spawn bindings, rather than directly creating live entities.

Activation requests must carry both:

- `expectedVersionStateEpoch` from Game Session so the workflow can fail fast if the target version was retired or changed mid-flight.
- `expectedGenerationConfigRevision` so pre-activation generation steps can prove they used the frozen publish identity rather than mutable tenant defaults.

These fields are sourced from the resolved launch descriptor and must remain immutable across retries of the same launch attempt. World Management must not re-read mutable template JSON, tenant defaults, or "latest READY patch" state during activation.

Before `PREPARING` or `ACTIVE` world state is accepted, World Management must also re-read the authoritative Game Design proof surfaces for the resolved `(tenantId, versionId)`:

- `GetPublishedReleaseBundle` must still return the same bundle identity, `generationConfigRevision`, and release-bundle ref.
- `GetVersionAssetArtifactState` must prove `artifactState=PUBLISHED`, the same attested `manifestHash`, and presence of every attested `requiredManifestAssetKeys[]`.
- `GetVersionState` must still report the same activation-eligible lifecycle state and the same `expectedVersionStateEpoch`.

If any of those proofs drift, activation fails closed with application-level attestation/version-state mismatch outcomes rather than proceeding on stale descriptor state.

The same `(tenantId, gameTemplateId, controlPlaneRequestId)` launch attempt must therefore replay against the same descriptor values on every retry, and a fresh launch attempt requires a new `controlPlaneRequestId` if it is allowed to resolve against newer valid published state. This is the workflow-local launch-attempt scope; step retry identity follows the shared [Transaction Strategies](../../system-architecture-transactions.md) contract and [ADR 0078](../../decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md), not this tuple alone.

In the target-state generation stages, `generationRequestId` names one generation-stage occurrence under the workflow/request scope. It is a stable semantic occurrence identity, not a Temporal run, worker, retry-attempt, or delivery identifier. Its allocation, propagation through generation activities, persistence with generation provenance, and digest-bound replay enforcement are unimplemented in the current slice; this statement adds no DTO, proto, code, owner, or derivation contract.

It never mutates template rows for Published versions; any structural changes to the world layout must occur through design-time workflows on Draft versions before publishing a new `versionId`. More broadly, world creation is allowed to invoke procedural generators only in **runtime/instance** mode as described in [Procedural Generation](../../system-architecture-procedural-generation.md); any attempt to write template rows from this workflow, even for non-Published versions, must be rejected by World Management validation. All template edits must flow through Game Design Service design-time APIs.

World creation uses **Class A (pre-activation) rollback semantics** from [Transaction Strategies](../../system-architecture-transactions.md#rollback-boundaries-by-operation-class): compensation is allowed until the instance is admitted for gameplay. Once admission opens, runtime mutations move to Class B retry/reconciliation semantics and are no longer rolled back through this workflow.

Activation-time input invariants:

- World creation must not consult mutable service defaults, tenant-scoped runtime knobs, feature flags, or hard-coded generator parameter sets when deciding the topology or semantic generation inputs for a published version.
- Any runtime generation that affects persisted instance topology must be driven from version-scoped published design inputs and the frozen `expectedGenerationConfigRevision`.
- Operational inputs such as shard placement or worker sizing may influence execution placement only; they must not change the generated room graph, room metadata, exits, or other persisted world semantics for a given launch descriptor.

The Class A/Class B boundary is persisted explicitly through a monotonic instance lifecycle state in World Management, under [ADR 0123](../../decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md):

- `world_instance_status=PREPARING` while world-lifecycle workflow steps are still compensatable.
- `world_instance_status=ACTIVE` once admission opens for gameplay.
- `world_instance_status=FAILED_PRE_ACTIVATION` when Class A preparation cannot converge and admission never opens.
- `world_instance_status=TERMINATING` after a fenced termination request from `PREPARING`, `FAILED_PRE_ACTIVATION`, or `ACTIVE`.
- `world_instance_status=TERMINATED` only after every owner in the frozen required cleanup-owner snapshot has acknowledged cleanup and the final fenced transition commits.

Allowed transitions are `PREPARING -> ACTIVE`, `PREPARING -> FAILED_PRE_ACTIVATION`, `PREPARING -> TERMINATING`, `FAILED_PRE_ACTIVATION -> TERMINATING`, `ACTIVE -> TERMINATING`, and `TERMINATING -> TERMINATED`. `FAILED_PRE_ACTIVATION` is terminal for admission and activation of that instance but does not prove cleanup completion; a fenced `FAILED_PRE_ACTIVATION -> TERMINATING` transition starts or resumes the same owner-scoped cleanup path, and a new instance is required to retry admission. Compensation logic in the world-lifecycle workflow is allowed only before `ACTIVE` commits; once status is `ACTIVE`, failures use Class B retry/reconciliation semantics instead of destructive rollback.

Initial NPC and item presence is modeled declaratively:

- Version-scoped spawn templates and population bindings are design-time data owned by the **World Management Service** and published under `(tenantId, versionId)`. They describe which entity templates (owned by Entity Management) may appear where.
- World-creation stages may materialize only instance-scoped spawn schedules derived from those published bindings for the target `(tenantId, gameInstanceId)`. They must not create new version-scoped bindings during activation.
- Creation of live entities and inventories remains the responsibility of Entity Management and Automation & Scripting workflows, typically driven at runtime via ticks or separate non-gameplay workflows, and is not performed directly by this world-creation workflow.

## Steps

1. **Create Starter Region Instance** – uses the published world topology for the chosen `tenantId` and `version_id` and inserts initial regions or instances using the local shard configuration (`WORLD_LOCAL_SHARD_ID`) for placement only. If startup requires runtime generation, the generator type, seed, and semantic config must be resolved from version-scoped published design inputs covered by `expectedGenerationConfigRevision`; the service must not fall back to default `SimpleDungeonGenerator` parameters or other mutable local defaults.
2. **Defer Initial Weather/Event Scheduling** – while the World weather aggregate selector and typed fenced command remain unresolved, the workflow does not insert or process an initial Weather event and does not mutate `region_instance.weather`; the stage is explicitly deferred/non-mutating. It must use the accepted effect-admission path before any future Weather event is scheduled, without choosing a region- or room-scoped aggregate here.
3. **Generate Terrain & Materialize Population Schedules** – optional stages that create terrain chunks and materialize instance-scoped spawn schedules for expansive worlds from already-published bindings. These stages must persist the `generationConfigRevision` actually used on each `generation_run` and fail if it differs from `expectedGenerationConfigRevision`.
4. **Activate Instance** – acquires the per-instance lifecycle fence, re-validates `expectedVersionStateEpoch`, verifies that all generation runs for this workflow resolved to `expectedGenerationConfigRevision`, and performs the one-way transition from `PREPARING` to `ACTIVE` after all required pre-activation writes succeed.

Initial-slice delivery expectation:

- The first live implementation cut now uses `PrepareWorldInstance`, `ActivatePreparedWorldInstance`, and `FailPreparedWorldInstance` to persist `world_instance`, starter `region_instance`, and runtime `zone_instance` / `room_instance` / `room_instance_exit` rows with fenced `lifecycle_epoch` transitions.
- Current starter-region preparation still stores hard-coded `SimpleDungeonGenerator` metadata rather than resolving the frozen published generator inputs and provenance required above.
- Initial event scheduling is not present in the current prepare path; initial Weather scheduling is intentionally deferred/non-mutating while its aggregate selector and typed fenced command remain unresolved, and the lifecycle snapshot has no durable per-stage outcome projection.
- Broader activation/cutover consumers and later runtime world-state families remain follow-on work on the same lifecycle seam rather than a separate activation model.

- The current live implementation cut implements the structural part of step 1 and the lifecycle transition in step 4. Step 2 remains deferred/non-mutating for Weather, and published generation-input convergence for step 1 remains incomplete; future work must extend the same workflow-state model rather than introducing a second activation path.
- Step 3 is optional for the initial slice unless the launched version actually requires expansive-world terrain generation or instance-scoped population schedule materialization.
- When step 3 is omitted for an initial-slice launch, the same launch descriptor and activation invariants still apply. The current implementation does not yet record that omission; the durable outcome below remains required before operators can distinguish intentional omission from not-started or failed work.

Required audit/output shape when optional step 3 is skipped:

- The workflow must emit a durable stage outcome for the omitted step under the same `controlPlaneRequestId` / `launchDescriptorId`.
- That outcome must distinguish `SKIPPED_NOT_REQUIRED` from `FAILED` or `NOT_STARTED`.
- Operators must be able to determine from persisted workflow state that terrain generation and/or population materialization were intentionally not required by the published launch descriptor.
- Logging & Admin workflow-status surfaces for this workflow must expose the same recorded outcome so operators do not have to inspect raw service tables to distinguish “not required” from “failed”.

Illustrative stage outcome:

```json
{
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "controlPlaneRequestId": "wc-77",
  "launchDescriptorId": "ld-55",
  "stepName": "generateTerrainAndMaterializePopulationSchedules",
  "outcome": "SKIPPED_NOT_REQUIRED",
  "reasonCode": "LAUNCH_DESCRIPTOR_DOES_NOT_REQUIRE_RUNTIME_GENERATION",
  "expectedGenerationConfigRevision": "genrev-2026-03-01",
  "recordedAt": "2026-03-13T10:15:00Z"
}
```

Illustrative operator-facing workflow status fragment:

```json
{
  "workflowFamily": "world-lifecycle",
  "workflowId": "world-lifecycle:7b3b074e-d597-4e9b-b96f-4f5946d26120:world-instance:9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78:request:wc-77",
  "workflowStatus": "RUNNING",
  "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
  "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
  "controlPlaneRequestId": "wc-77",
  "steps": [
    {
      "stepName": "generateTerrainAndMaterializePopulationSchedules",
      "status": "COMPLETED",
      "recordedOutcome": "SKIPPED_NOT_REQUIRED",
      "reasonCode": "LAUNCH_DESCRIPTOR_DOES_NOT_REQUIRE_RUNTIME_GENERATION"
    }
  ]
}
```

### Workflow Activity Idempotency

World creation steps write durable instance rows and must be safely retryable under the general workflow/request and step-guard contract owned by [Transaction Strategies](../../system-architecture-transactions.md) and explained by [ADR 0078](../../decisions/adr-0078-digest-bound-workflow-and-step-retry-identities.md). This adopter document supplies workflow-local inputs without redefining the guard policy:

- Stable business scope is the resolved launch attempt for `<tenantId, gameInstanceId, controlPlaneRequestId>` and its immutable `launchDescriptorId`.
- The stable step name identifies the lifecycle operation, while a deterministic occurrence key distinguishes each logical occurrence of that operation; for generation stages, the target-state `generationRequestId` is part of that occurrence input so retries across workflow runs converge on the same logical generation result. Its allocation and propagation remain unimplemented as noted above.
- The execution role distinguishes forward preparation/activation work from applicable pre-activation compensation; it is not inferred from a Temporal run, process, retry attempt, or message/delivery identifier.
- The immutable request-digest input covers the exact resolved launch descriptor and step request values used by the operation, including `expectedGenerationConfigRevision` and, where applicable, `generationRequestId`. Digest construction, persistence, comparison, and guard storage remain governed by the shared contract and are not specified here.

On a retry of the same workflow identity:

- If the guard indicates the step has already completed successfully, the step must become a no-op and return success.
- If partial writes exist without a completed guard record (for example due to a crash), the step must either reconcile deterministically (preferred) or fail fast with a clear operator-visible error so the workflow can be retried safely after cleanup.

If retries are exhausted before admission:

- The workflow marks `world_instance_status=FAILED_PRE_ACTIVATION`.
- No gameplay admission is permitted for that `gameInstanceId`.
- `FAILED_PRE_ACTIVATION` is terminal for admission and activation of that `gameInstanceId`, but it is not proof of cleanup convergence; operators must create a new instance with a new `gameInstanceId` to retry admission.

The target guard must be enforced with the step’s durable writes so “step completed” cannot be recorded without the corresponding instance rows. The current World Management implementation preserves `controlPlaneRequestId`, lifecycle fencing, and the implemented `generationConfigRevision` in its command paths; the target `generationRequestId` occurrence identity and its propagation remain unimplemented. It also does not yet demonstrate the complete occurrence/role/digest-bound guard or fail-closed conflict behavior across workflow retries. Current lifecycle tests prove the implemented lifecycle and idempotency seams, not the full ADR 0078 retry, conflict, crash, replay, and durable-guard proof; that implementation and proof drift remains open.

The current `TemporalWorldLifecycleOrchestrator` workflow ID is derived only from `<tenantId, gameInstanceId>`, even though the prepared request also carries `controlPlaneRequestId` and `launchDescriptorId`. When Temporal reports `AlreadyStarted`, the orchestrator ignores the exception and queries the existing workflow without exact-comparing those launch identities or their request digest. The current activation wait also requires a later `ACTIVE` snapshot/epoch within a fixed ten-second polling window; an exact retry can therefore time out while the existing workflow is still in `PREPARING` or processing the activation signal. In addition, activation and failure activities validate the old epoch before their terminal-state check, so a response lost after a local commit retries as stale; StartSession compensation can consequently leave an already-`ACTIVE` World row orphaned. Once fail or terminate marks the long-lived workflow complete, the orchestrator also signals that closed workflow on a later exact terminal retry rather than reconciling the durable terminal row. These are current identity, replay/conflict, compensation, and terminal-reconciliation gaps; durable operation results, ACTIVE-aware compensation, durable-row reconciliation before signaling, and focused lost-response/closed-workflow proof remain required.

Temporal owns orchestration and workflow progress using the canonical `world-lifecycle` workflow identity; World Management's durable lifecycle row and epoch remain authoritative for lifecycle state. Operators can inspect progress through the normal lifecycle read surface and any Temporal-backed operator tooling that projects the same `workflowId`.

See [World Management Service](README.md) for additional service context.

See [Transaction Strategies](../../system-architecture-transactions.md) for the canonical boundary between ticks, short synchronous saga orchestration, and durable Temporal workflows.

## Instance Termination and Cleanup

Instance expiry and operator-driven shutdown must use an explicit cross-service termination workflow rather than independent cleanup jobs.

- Game Session must first mark the target instance non-admissible/draining before World starts termination.
- World Management starts or resumes the canonical `world-lifecycle` Temporal workflow and performs a storage-level compare-and-set from `PREPARING`, `FAILED_PRE_ACTIVATION`, or `ACTIVE` to `TERMINATING` under the expected lifecycle epoch. A stale activation cannot reopen the instance.
- When a stable `terminationRequestId` begins, the workflow freezes and persists the exact required cleanup-owner set together with the matching ownership-registry revision as that termination's owner snapshot, with separate per-owner cleanup acknowledgement state. Every retry of that exact request, per-owner cleanup request/acknowledgement, and final `TERMINATING -> TERMINATED` compare-and-set must bind and exact-compare the same snapshot; only owners in it are required for that termination attempt. A mid-flight ownership-registry change cannot silently add or drop owners or claim completion. Each owner runs an idempotent local cleanup step under its own durable guard; Temporal workflow/run identity is execution trace only. A `TERMINATED` fast path must first exact-compare the supplied `terminationRequestId` with the retained terminal identity: a matching retry returns the stored terminal success, while a mismatch returns `IDEMPOTENCY_CONFLICT` without changing lifecycle state. The focused proof must cover that comparison and the failure/retry path that leaves the instance in `TERMINATING` until all frozen owners acknowledge.
- World Management finalizes world-side cleanup and marks the instance `TERMINATED` only after every owner in the frozen snapshot acknowledges cleanup and the final compare-and-set succeeds against that same snapshot. The target World acknowledgement covers completion of every registered World-owned S3 family in the canonical [replacement-instance state classification](runtime-and-data.md#replacement-instance-state-classification), while Entity Management removes its synthetic room-ground containers and containment rows. World-owned local cleanup must complete before World acknowledges its participant obligation; it does not replace the other owners' acknowledgements.
- If preparation reaches `FAILED_PRE_ACTIVATION`, its admission remains closed while the separate owner-scoped cleanup state can continue or be repaired; the failure result is not cleanup proof.
- If cleanup fails after admission is already closed, the world remains `TERMINATING` and the same termination workflow identity must retry to convergence instead of restoring the instance to live admission.

`expires_at` jobs for a complete `world_instance` with its own `gameInstanceId` must enqueue this termination workflow; they must not hard-delete world rows before cross-service cleanup convergence is confirmed. Expiry of a legacy zone-child `instance` requires its separate scoped, idempotent cleanup and must not transition or terminate the parent `world_instance` lifecycle.

## Activation vs Termination Fencing

Activation and termination share one lifecycle fence per `(tenantId, gameInstanceId)`:

- Activation acquires the fence before committing `PREPARING -> ACTIVE`.
- Termination acquires the same fence before committing `PREPARING -> TERMINATING`, `FAILED_PRE_ACTIVATION -> TERMINATING`, or `ACTIVE -> TERMINATING`.
- If both workflows race, only the storage-level CAS against the current state and epoch may transition lifecycle state; stale-token attempts fail and must retry from fresh state.

## Replacement Cutover Hold and Termination Ordering

Replacement cutover has one additional World-owned coordination record separate from the lifecycle row. After the target `PREPARING -> ACTIVE` CAS commits, World allocates one opaque `cutoverHoldId` and equality-only `cutoverHoldFence` for the execution, or reuses the exact existing pair on retry. It locks source and target lifecycle rows in a stable order, requires the exact source and target `ACTIVE` state/epoch proofs and the complete prepared-upgrade/request/digest identity, exact `playableStateNamespaceId`/`playableStateScope`, the applicable canonical private/playtest lifecycle proof tuple `{playtestLifecycleId, playtestStateGeneration}`, realm/instance/version, and pointer-version identity, rejects a conflicting nonterminal hold, and commits the hold with World-database `expiresAt`. This record is a cutover hold, not a new lifecycle state or an ownership transfer. Public-production replacement omits both playtest fields and rejects supplied values.

Every source or target termination CAS includes absence of a nonterminal cutover hold for that instance. While held, termination is pending/retryable and cannot advance the lifecycle epoch. Game Session binds the hold identity/fence, the exact `playableStateNamespaceId`/`playableStateScope` pair, the applicable canonical private/playtest lifecycle proof tuple, and exact lifecycle proofs into its local pointer, audit, prepared-execution/result, source-cleanup, and drain-fence transaction. World finalizes the hold only after authoritative Game Session post-swap readback proves that exact namespace/scope-and-lifecycle-proof-bound transaction committed. A lost response is reconciled by the exact hold identity and owner-local reads; abort is permitted only with proof that the pointer transaction did not commit and the prior pointer remains authoritative. Contradictory or unavailable evidence leaves `RECONCILIATION_REQUIRED`, which continues to block termination. `expiresAt` is diagnostic/repair input only and never auto-releases an unresolved hold. The current workflow lacks this hold record, coordinated wire/RPC surface, and focused proof; this documentation-only parcel does not add or claim those artifacts, which remain required implementation and proof work.

## Version Switching and Instance Data

A `gameInstanceId` is always tied to a single `runtime_version` and the
instance data derived from that version:

- Initial `*_instance` rows for a given `gameInstanceId` may be constructed from the templates for that instance’s `runtime_version` and the frozen published generation inputs. After generation finalizes, the committed instance topology or retained immutable topology artifact is authoritative; recovery restores that stored result, a retained finalized instance-topology artifact bound to `(tenantId, versionId, gameInstanceId)`, or an eligible backup rather than re-executing a historical generator from seed and metadata. A backup is eligible only when its `tenantId`, `versionId`, `gameInstanceId`, `expectedGenerationConfigRevision`, and finalized topology digest exactly match the recovering instance; a mismatch is rejected and recovery uses another authoritative source. There is no cross-version mixing of instance data and no reuse of instance layouts across different `gameInstanceId` values.
- Moving a game to a different version is modeled as starting a **new** game
  instance with a fresh `gameInstanceId` and running the world-lifecycle workflow
  again for the new `(tenantId, versionId)`. Existing instances continue to use
  their original templates until they are shut down.
- Operational tooling should not attempt to “retarget” an existing
  `gameInstanceId` to a new `runtime_version` while reusing its world instance
  rows; doing so would violate the invariant above and can lead to corrupted
  world state.

This policy keeps the world-creation workflow simple and ensures that every
game instance has a self-consistent view of templates and instance data for its
chosen version.

Short-lived, runtime-generated dungeons or similar instanced content are treated as ephemeral and exist only for the lifetime of a specific `gameInstanceId`; their stored topology may be discarded only with that instance’s lifecycle. FireMUD never regenerates discarded topology under the same `gameInstanceId`: regeneration creates a new instance with a new `gameInstanceId` and resets all dependent runtime state. Long-lived overworld-style instance layouts that must survive restarts retain their committed topology or finalized artifact and remain bound to the original `(tenantId, runtime_version, gameInstanceId)` tuple; upgrading to a new `runtime_version` always uses a new `gameInstanceId` and runs world creation for that instance rather than attempting to migrate or reuse prior instance layouts.

For clarity, activation-time code paths must not use method or table names implying creation of new version-scoped rules, templates, or bindings. Any operation named `register*Rule`, `create*Binding`, or equivalent is design-time unless the target rows are explicitly instance-scoped.
