# World Creation Workflow

World creation is a long-running process that prepares the initial world state for a new **game instance** using already-published world data for a given `tenantId`. The workflow runs on the shared **Temporal** substrate so prepare/activate/fail/terminate coordination survives service restarts, keeps deterministic workflow identity, and exposes operator-visible execution progress without pulling gameplay runtime onto the workflow engine. Temporal coordinates the control-plane work; the World Management `world_instance` lifecycle row and its monotonic epoch remain authoritative for admission and lifecycle state. World Management's activation lifecycle surface is invoked when the platform provisions a new game instance for an existing tenant, typically from the Game Session Service. The identifiers involved are:

- `tenantId` – identifies the game (tenant) as described in
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- `versionId` – identifies the published world/template data to use, as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `generationConfigRevision` – the frozen generation-config identity recorded at publish for the target `(tenantId, versionId)`; activation must fail if this identity cannot be resolved.
- `gameInstanceId` – identifies the specific running world instance recorded in
  the Game Session Service.

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

The target workflow uses the published world topology for the chosen `tenantId` and `version_id`, inserts a starter region instance, schedules initial events, and can generate terrain chunks and materialize instance-scoped population schedules for expansive worlds. Activation-time topology must be derived only from the attested published template graph plus runtime generation runs whose canonical inputs are covered by the same published `generationConfigRevision`. Throughout the workflow, the Temporal workflow and its activities:

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

The same `(tenantId, gameTemplateId, controlPlaneRequestId)` launch attempt must therefore replay against the same descriptor values on every retry, and a fresh launch attempt requires a new `controlPlaneRequestId` if it is allowed to resolve against newer valid published state.

It never mutates template rows for Published versions; any structural changes to the world layout must occur through design-time workflows on Draft versions before publishing a new `versionId`. More broadly, world creation is allowed to invoke procedural generators only in **runtime/instance** mode as described in [Procedural Generation](../../system-architecture-procedural-generation.md); any attempt to write template rows from this workflow, even for non-Published versions, must be rejected by World Management validation. All template edits must flow through Game Design Service design-time APIs.

World creation uses **Class A (pre-activation) rollback semantics** from [Transaction Strategies](../../system-architecture-transactions.md#rollback-boundaries-by-operation-class): compensation is allowed until the instance is admitted for gameplay. Once admission opens, runtime mutations move to Class B retry/reconciliation semantics and are no longer rolled back through this workflow.

Activation-time input invariants:

- World creation must not consult mutable service defaults, tenant-scoped runtime knobs, feature flags, or hard-coded generator parameter sets when deciding the topology or semantic generation inputs for a published version.
- Any runtime generation that affects persisted instance topology must be driven from version-scoped published design inputs and the frozen `expectedGenerationConfigRevision`.
- Operational inputs such as shard placement or worker sizing may influence execution placement only; they must not change the generated room graph, room metadata, exits, or other persisted world semantics for a given launch descriptor.

The Class A/Class B boundary and termination state are persisted explicitly on the authoritative World Management `world_instance` row:

- `world_instance_status=PREPARING` while world-lifecycle workflow steps are still compensatable and gameplay admission is closed.
- `world_instance_status=ACTIVE` once preparation has completed and gameplay admission may open.
- `world_instance_status=FAILED_PRE_ACTIVATION` when Class A preparation cannot converge and admission never opens for that `gameInstanceId`.
- `world_instance_status=TERMINATING` once a fenced termination request has closed or superseded activation and cross-service cleanup is in progress.
- `world_instance_status=TERMINATED` only after every registered durable `gameInstanceId` data owner has acknowledged its cleanup obligation.

The row carries a monotonic `lifecycle_epoch`. Every lifecycle mutation is a storage-level compare-and-set against the expected state and epoch; Temporal status, worker memory, and operator projections are not lifecycle authority. Allowed forward transitions are `PREPARING -> ACTIVE`, `PREPARING -> FAILED_PRE_ACTIVATION`, `PREPARING -> TERMINATING`, `ACTIVE -> TERMINATING`, and `TERMINATING -> TERMINATED`. `FAILED_PRE_ACTIVATION` and `TERMINATED` are lifecycle-terminal for that `gameInstanceId`.

Compensation logic in the world-lifecycle workflow is allowed only before `ACTIVE` commits. Once status is `ACTIVE`, failures use Class B retry/reconciliation semantics instead of destructive rollback. `FAILED_PRE_ACTIVATION` proves only that activation and admission are permanently closed; it does not prove cleanup completion. Pre-activation cleanup progress is tracked separately through durable owner-scoped acknowledgement state and can continue after the lifecycle has reached `FAILED_PRE_ACTIVATION`.

Initial NPC and item presence is modeled declaratively:

- Version-scoped spawn templates and population bindings are design-time data owned by the **World Management Service** and published under `(tenantId, versionId)`. They describe which entity templates (owned by Entity Management) may appear where.
- World-creation stages may materialize only instance-scoped spawn schedules derived from those published bindings for the target `(tenantId, gameInstanceId)`. They must not create new version-scoped bindings during activation.
- Creation of live entities and inventories remains the responsibility of Entity Management and Automation & Scripting workflows, typically driven at runtime via ticks or separate non-gameplay workflows, and is not performed directly by this world-creation workflow.

## Steps

1. **Create Starter Region Instance** – uses the published world topology for the chosen `tenantId` and `version_id` and inserts initial regions or instances using the local shard configuration (`WORLD_LOCAL_SHARD_ID`) for placement only. If startup requires runtime generation, the generator type, seed, and semantic config must be resolved from version-scoped published design inputs covered by `expectedGenerationConfigRevision`; the service must not fall back to default `SimpleDungeonGenerator` parameters or other mutable local defaults.
2. **Schedule Initial Events** – inserts world events such as an initial weather state so `WorldEventService` can apply them after the world starts.
3. **Generate Terrain & Materialize Population Schedules** – optional stages that create terrain chunks and materialize instance-scoped spawn schedules for expansive worlds from already-published bindings. These stages must persist the `generationConfigRevision` actually used on each `generation_run` and fail if it differs from `expectedGenerationConfigRevision`.
4. **Activate Instance** – acquires the per-instance lifecycle fence, re-validates `expectedVersionStateEpoch`, verifies that all generation runs for this workflow resolved to `expectedGenerationConfigRevision`, and performs the one-way transition from `PREPARING` to `ACTIVE` after all required pre-activation writes succeed.

Initial-slice delivery expectation:

- The first live implementation cut now uses `PrepareWorldInstance`, `ActivatePreparedWorldInstance`, and `FailPreparedWorldInstance` to persist `world_instance`, starter `region_instance`, and runtime `zone_instance` / `room_instance` / `room_instance_exit` rows with fenced `lifecycle_epoch` transitions.
- Current starter-region preparation still stores hard-coded `SimpleDungeonGenerator` metadata rather than resolving the frozen published generator inputs and provenance required above.
- Initial event scheduling is not present in the current prepare path, and the lifecycle snapshot has no durable per-stage outcome projection.
- Broader activation/cutover consumers and later runtime world-state families remain follow-on work on the same lifecycle seam rather than a separate activation model.
- Termination during `PREPARING`, separate failed-instance cleanup state, an extensible durable-owner registry, and all-owner acknowledgement gating are not implemented in the first cut.

- The current live implementation cut implements the structural part of step 1 and the lifecycle transition in step 4. Step 2 and published generation-input convergence for step 1 remain incomplete; future work must extend the same workflow-state model rather than introducing a second activation path.
- Step 3 is optional for the initial slice unless the launched version actually requires expansive-world terrain generation or instance-scoped population schedule materialization.
- When step 3 is omitted for an initial-slice launch, the same launch descriptor and activation invariants still apply. The current implementation does not yet record that omission; the durable outcome below remains required before operators can distinguish intentional omission from not-started or failed work.

Required audit/output shape when optional step 3 is skipped:

- The workflow must emit a durable stage outcome for the omitted step under the same `worldCreationRequestId` / `launchDescriptorId`.
- That outcome must distinguish `SKIPPED_NOT_REQUIRED` from `FAILED` or `NOT_STARTED`.
- Operators must be able to determine from persisted workflow state that terrain generation and/or population materialization were intentionally not required by the published launch descriptor.
- Logging & Admin workflow-status surfaces for this workflow must expose the same recorded outcome so operators do not have to inspect raw service tables to distinguish “not required” from “failed”.

Illustrative stage outcome:

```json
{
  "tenantId": "t1",
  "gameInstanceId": "g-100",
  "worldCreationRequestId": "wc-77",
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
  "workflowId": "world-lifecycle:t1:world-instance:g-100",
  "workflowStatus": "RUNNING",
  "tenantId": "t1",
  "gameInstanceId": "g-100",
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

World creation steps write durable instance rows and must be safely retryable. Each externally retryable step must implement a durable idempotency guard keyed by a stable business idempotency key plus step identity, at minimum:

- `(tenantId, gameInstanceId, worldCreationRequestId, stepName, expectedGenerationConfigRevision)`

For generation stages, the idempotency key must additionally include `generationRequestId` so retries across different workflow runs converge on one logical result.

On a retry of the same workflow identity:

- If the guard indicates the step has already completed successfully, the step must become a no-op and return success.
- If partial writes exist without a completed guard record (for example due to a crash), the step must either reconcile deterministically (preferred) or fail fast with a clear operator-visible error so the workflow can be retried safely after cleanup.

If retries are exhausted before admission:

- The workflow marks `world_instance_status=FAILED_PRE_ACTIVATION`.
- No gameplay admission is permitted for that `gameInstanceId`.
- `FAILED_PRE_ACTIVATION` is terminal for that `gameInstanceId`; operators must create a new instance with a new `gameInstanceId` for retry.

This guard must be enforced in the same local transaction as the step’s durable writes so “step completed” cannot be recorded without the corresponding instance rows. Lifecycle mutations additionally use storage-level compare-and-set predicates on the expected state and `lifecycle_epoch`. For steps that invoke runtime generation, the guard must also carry a deterministic `generationRequestId` so retries across new workflow runs resolve to the same generation run instead of creating duplicate topology writes. An activity that loses its response after local commit must reconstruct and return the durable prior result on retry. See `design/architecture/system-architecture-transactions.md` for idempotency and retry expectations.

Temporal owns durable coordination history for the canonical `world-lifecycle` workflow identity, including retries, waits, and operator-visible progress. It does not own lifecycle truth. Operators inspect the authoritative World Management lifecycle row and cleanup acknowledgements alongside Temporal-backed tooling that projects the same `workflowId`. Routine gameplay and tick execution do not call Temporal or wait for workflow status; they consult the existing admission and lifecycle fences only at lifecycle-sensitive boundaries.

See [World Management Service](README.md) for additional service context.

See [Transaction Strategies](../../system-architecture-transactions.md) for the canonical boundary between ticks, short synchronous saga orchestration, and durable Temporal workflows.

## Instance Termination and Cleanup

Instance expiry and operator-driven shutdown must use an explicit cross-service termination workflow rather than independent cleanup jobs.

- For an `ACTIVE` instance, Game Session first marks the target non-admissible/draining. A `PREPARING` instance is already non-admissible.
- World Management starts or resumes the canonical `world-lifecycle` Temporal workflow and performs a storage-level compare-and-set from `PREPARING` or `ACTIVE` to `TERMINATING` under the expected lifecycle epoch. A successful `PREPARING -> TERMINATING` transition makes any stale activation attempt fail its compare-and-set and immediately starts cleanup of preparation outputs.
- At termination admission, World Management records the stable `terminationRequestId`, the complete registered set of durable data owners for that `gameInstanceId`, and separate per-owner cleanup acknowledgement state.
- Each owner runs an idempotent local cleanup step keyed by `(tenantId, gameInstanceId, terminationRequestId, stepName)` and records its acknowledgement in owner-local durable storage. Temporal `workflowId` and run identity are execution trace only. Entity Management removes synthetic room-ground containers and containment rows; World Management cleans its owned runtime rows while retaining the lifecycle and cleanup evidence required for audit.
- World Management marks the instance `TERMINATED` only after every required owner acknowledgement is durably present and its final `TERMINATING -> TERMINATED` compare-and-set succeeds. Missing, failed, or unknown owner state fails closed.
- Any service that introduces a durable data family keyed by `gameInstanceId` must register that family and define its replacement-state classification, termination cleanup, stable operation identity, acknowledgement, retry, and retention behavior before launch paths may write it.
- If cleanup fails after admission is closed, the world remains `TERMINATING` and the same termination workflow identity retries or awaits operator repair instead of restoring live admission.
- If preparation instead reaches `FAILED_PRE_ACTIVATION`, its lifecycle remains admission-terminal while the same owner-scoped cleanup model records and drives cleanup independently; cleanup completion must not be inferred from the failure state.

The current first implementation cut exposes termination synchronously through `TerminateWorldInstance(tenantId, gameInstanceId, expectedLifecycleEpoch, terminationRequestId)`, with Game Session reading fresh lifecycle state through `GetWorldInstanceLifecycle` immediately before termination. It accepts the implemented active-instance path, invokes Entity Management cleanup, and then hard-deletes World-owned `world_event`, `room_instance_exit`, `room_instance`, `zone_instance`, and `region_instance` rows while retaining `world_instance`. It does not yet implement `PREPARING` termination, separate cleanup acknowledgements, a complete owner registry, or all-owner finalization.

`expires_at` jobs must enqueue this termination workflow; they must not hard-delete world rows for a `gameInstanceId` before cross-service cleanup convergence is confirmed.

## Activation vs Termination Fencing

Activation and termination share one lifecycle fence per `(tenantId, gameInstanceId)`:

- Activation performs a storage-level compare-and-set before committing `PREPARING -> ACTIVE`.
- Termination performs a storage-level compare-and-set under the same epoch before committing either `PREPARING -> TERMINATING` or `ACTIVE -> TERMINATING`.
- If activation and termination race from `PREPARING`, only one compare-and-set can advance the lifecycle row. A stale activation never reopens an instance that termination fenced, and a stale termination must reread the authoritative state rather than guessing the winner from Temporal history.

## Version Switching and Instance Data

A `gameInstanceId` is always tied to a single `runtime_version` and the
instance data derived from that version:

- All `*_instance` rows for a given `gameInstanceId` must be derivable from the
  templates for that instance’s `runtime_version` plus any persisted procedural
  generation metadata (for example `generatorType`, `seed`, and an immutable
  `configSnapshot` with `schemaVersion`). There is no cross-version mixing of
  instance data and no reuse of instance layouts across different
  `gameInstanceId` values.
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

Short-lived, runtime-generated dungeons or similar instanced content are treated as ephemeral and exist only for the lifetime of a specific `gameInstanceId`. Long-lived overworld-style instance layouts that must survive restarts remain bound to the original `(tenantId, runtime_version, gameInstanceId)` tuple; upgrading to a new `runtime_version` always uses a new `gameInstanceId` and reruns world creation rather than attempting to migrate or reuse prior instance layouts.

For clarity, activation-time code paths must not use method or table names implying creation of new version-scoped rules, templates, or bindings. Any operation named `register*Rule`, `create*Binding`, or equivalent is design-time unless the target rows are explicitly instance-scoped.
