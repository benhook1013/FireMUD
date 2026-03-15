# World Creation Workflow

World creation is a long-running process that prepares the initial world state for a new **game instance** using already-published world data for a given `tenantId`. The workflow uses the shared **Saga** utilities from `firemud-common` so each step can be rolled back if another step fails. `WorldCreationService` is invoked when the platform provisions a new game instance for an existing tenant, typically from the Game Session Service. The identifiers involved are:

- `tenantId` – identifies the game (tenant) as described in
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- `versionId` – identifies the published world/template data to use, as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `generationConfigRevision` – the frozen generation-config identity recorded at publish for the target `(tenantId, versionId)`; activation must fail if this identity cannot be resolved.
- `gameInstanceId` – identifies the specific running world instance recorded in
  the Game Session Service.

World creation consumes a previously resolved immutable launch descriptor for the attempt. At minimum this descriptor carries:

- `launchDescriptorId`
- `tenantId`
- `gameTemplateId` when launch originated from a template
- resolved `versionId`
- resolved `scriptPatchVersion` (or explicit null)
- `expectedVersionStateEpoch`
- `expectedGenerationConfigRevision`
- any approved `remapSetId`
- resolved runtime flags/defaults needed by downstream services

The implementation uses the published world topology for the chosen `tenantId` and `version_id`, inserts a starter region instance, schedules initial events, and can generate terrain chunks and materialize instance-scoped population schedules for expansive worlds. Activation-time topology must be derived only from the attested published template graph plus runtime generation runs whose canonical inputs are covered by the same published `generationConfigRevision`. Throughout the workflow, the Saga:

- Reads only **template/topology** rows keyed by `(tenantId, versionId)` (for example `region_template`, `room_template`, or authored generation metadata); and
- Writes only **instance** rows keyed by `(tenantId, gameInstanceId)` (for example `region_instance`, `room_instance`, `world_event`) plus optional **instance-scoped population schedules/materializations** derived from already-published spawn bindings, rather than directly creating live entities.

Activation requests must carry both:

- `expectedVersionStateEpoch` from Game Session so the workflow can fail fast if the target version was retired or changed mid-flight.
- `expectedGenerationConfigRevision` so pre-activation generation steps can prove they used the frozen publish identity rather than mutable tenant defaults.

These fields are sourced from the resolved launch descriptor and must remain immutable across retries of the same launch attempt. World Management must not re-read mutable template JSON, tenant defaults, or "latest READY patch" state during activation.

It never mutates template rows for Published versions; any structural changes to the world layout must occur through design-time workflows on Draft versions before publishing a new `versionId`. More broadly, world creation is allowed to invoke procedural generators only in **runtime/instance** mode as described in [Procedural Generation](../../system-architecture-procedural-generation.md); any attempt to write template rows from this Saga, even for non-Published versions, must be rejected by World Management validation. All template edits must flow through Game Design Service design-time APIs.

World creation uses **Class A (pre-activation) rollback semantics** from [Transaction Strategies](../../system-architecture-transactions.md#rollback-boundaries-by-operation-class): compensation is allowed until the instance is admitted for gameplay. Once admission opens, runtime mutations move to Class B retry/reconciliation semantics and are no longer rolled back through this Saga.

Activation-time input invariants:

- World creation must not consult mutable service defaults, tenant-scoped runtime knobs, feature flags, or hard-coded generator parameter sets when deciding the topology or semantic generation inputs for a published version.
- Any runtime generation that affects persisted instance topology must be driven from version-scoped published design inputs and the frozen `expectedGenerationConfigRevision`.
- Operational inputs such as shard placement or worker sizing may influence execution placement only; they must not change the generated room graph, room metadata, exits, or other persisted world semantics for a given launch descriptor.

The Class A/Class B boundary is persisted explicitly through a monotonic instance lifecycle state in World Management:

- `world_instance_status=PREPARING` while world-creation Saga steps are still compensatable.
- `world_instance_status=ACTIVE` once admission opens for gameplay.
- `world_instance_status=FAILED_PRE_ACTIVATION` when Class A preparation cannot converge and admission never opens.

Transitions are one-way for a given `gameInstanceId` once terminal states are reached. Compensation logic in the world-creation Saga is allowed only while status is `PREPARING`; once status is `ACTIVE`, failures must be handled by Class B retry/reconciliation semantics instead of destructive rollback.

Initial NPC and item presence is modeled declaratively:

- Version-scoped spawn templates and population bindings are design-time data owned by the **World Management Service** and published under `(tenantId, versionId)`. They describe which entity templates (owned by Entity Management) may appear where.
- World-creation stages may materialize only instance-scoped spawn schedules derived from those published bindings for the target `(tenantId, gameInstanceId)`. They must not create new version-scoped bindings during activation.
- Creation of live entities and inventories remains the responsibility of Entity Management and Automation & Scripting workflows, typically driven at runtime via ticks or separate non-gameplay Sagas, and is not performed directly by this world-creation Saga.

## Steps

1. **Create Starter Region Instance** – uses the published world topology for the chosen `tenantId` and `version_id` and inserts initial regions or instances using the local shard configuration (`WORLD_LOCAL_SHARD_ID`) for placement only. If startup requires runtime generation, the generator type, seed, and semantic config must be resolved from version-scoped published design inputs covered by `expectedGenerationConfigRevision`; the service must not fall back to default `SimpleDungeonGenerator` parameters or other mutable local defaults.
2. **Schedule Initial Events** – inserts world events such as an initial weather state so `WorldEventService` can apply them after the world starts.
3. **Generate Terrain & Materialize Population Schedules** – optional stages that create terrain chunks and materialize instance-scoped spawn schedules for expansive worlds from already-published bindings. These stages must persist the `generationConfigRevision` actually used on each `generation_run` and fail if it differs from `expectedGenerationConfigRevision`.
4. **Activate Instance** – acquires the per-instance lifecycle fence, re-validates `expectedVersionStateEpoch`, verifies that all generation runs for this workflow resolved to `expectedGenerationConfigRevision`, and performs the one-way transition from `PREPARING` to `ACTIVE` after all required pre-activation writes succeed.

Initial-slice delivery expectation:

- The first implementation slice must implement steps 1, 2, and 4.
- Step 3 is optional for the initial slice unless the launched version actually requires expansive-world terrain generation or instance-scoped population schedule materialization.
- When step 3 is omitted for an initial-slice launch, the same launch descriptor and activation invariants still apply; the workflow simply records that no runtime generation/materialization step was required for that `gameInstanceId`.

Required audit/output shape when optional step 3 is skipped:

- The workflow must emit a durable stage outcome for the omitted step under the same `worldCreationRequestId` / `launchDescriptorId`.
- That outcome must distinguish `SKIPPED_NOT_REQUIRED` from `FAILED` or `NOT_STARTED`.
- Operators must be able to determine from persisted workflow state that terrain generation and/or population materialization were intentionally not required by the published launch descriptor.
- Logging & Admin saga-status surfaces for this workflow must expose the same recorded outcome so operators do not have to inspect raw service tables to distinguish “not required” from “failed”.

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

Illustrative operator-facing saga status fragment:

```json
{
  "sagaName": "createWorld",
  "sagaInstanceId": "saga-9001",
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

### Saga Step Idempotency

World creation steps write durable instance rows and must be safely retryable. Each externally retryable step must implement a durable idempotency guard keyed by a stable business idempotency key plus step identity, at minimum:

- `(tenantId, gameInstanceId, worldCreationRequestId, stepName, expectedGenerationConfigRevision)`

For generation stages, the idempotency key must additionally include `generationRequestId` so retries across different `sagaInstanceId` values converge on one logical result.

On a retry of the same saga instance:

- If the guard indicates the step has already completed successfully, the step must become a no-op and return success.
- If partial writes exist without a completed guard record (for example due to a crash), the step must either reconcile deterministically (preferred) or fail fast with a clear operator-visible error so the saga can be retried safely after cleanup.

If retries are exhausted before admission:

- The workflow marks `world_instance_status=FAILED_PRE_ACTIVATION`.
- No gameplay admission is permitted for that `gameInstanceId`.
- `FAILED_PRE_ACTIVATION` is terminal for that `gameInstanceId`; operators must create a new instance with a new `gameInstanceId` for retry.

This guard must be enforced in the same local transaction as the step’s durable writes so “step completed” cannot be recorded without the corresponding instance rows. For steps that invoke runtime generation, the guard must also carry a deterministic `generationRequestId` so retries across new saga instances resolve to the same generation run instead of creating duplicate topology writes. See `design/architecture/system-architecture-transactions.md` for idempotency and retry expectations.

```java
SagaBuilder builder = new SagaBuilder("createWorld");
builder
    .step("createStarterRegion", () -> createStarterRegionInstance(
        tenantId, versionId, gameInstanceId, worldCreationRequestId, expectedGenerationConfigRevision))
    .step("scheduleEvents", () -> scheduleInitialEvents(tenantId, gameInstanceId))
    .step("materializePopulationSchedules", () -> materializePopulationSchedules(
        tenantId, versionId, gameInstanceId, generationRequestId, expectedGenerationConfigRevision))
    .step("activateInstance", () -> activateInstance(
        tenantId, gameInstanceId, expectedVersionStateEpoch, expectedGenerationConfigRevision));
sagaRunner.run(builder.build());
```

The saga state is stored in the `saga_instance` and `saga_step` tables defined in the common library. Operators can inspect progress through the Logging & Admin Service's saga dashboard.

See [World Management Service](README.md) for additional service context.

See [Transaction Strategies](../../system-architecture-transactions.md) for background on how sagas are used across FireMUD.

## Instance Termination and Cleanup

Instance expiry and operator-driven shutdown must use an explicit cross-service termination workflow rather than independent cleanup jobs.

- Game Session must first mark the target instance non-admissible/draining before World starts termination.
- World Management starts an `InstanceTermination` Saga and marks the instance `TERMINATING`.
- Entity Management runs an idempotent cleanup step keyed by `(tenantId, gameInstanceId, terminationRequestId, stepName)` (with `sagaInstanceId` as execution trace only) that removes synthetic room-ground containers and containment rows scoped to the terminating instance.
- World Management finalizes world-side cleanup and marks the instance `TERMINATED` only after Entity Management confirms cleanup completion.

`expires_at` jobs must enqueue this termination workflow; they must not hard-delete world rows for a `gameInstanceId` before cross-service cleanup convergence is confirmed.

## Activation vs Termination Fencing

Activation and termination share one lifecycle fence per `(tenantId, gameInstanceId)`:

- Activation acquires the fence before committing `PREPARING -> ACTIVE`.
- Termination acquires the same fence before committing `ACTIVE -> TERMINATING`.
- If both workflows race, only the workflow holding the current fence token may transition lifecycle state; stale-token attempts fail and must retry from fresh state.

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
  instance with a fresh `gameInstanceId` and running the world-creation Saga
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
