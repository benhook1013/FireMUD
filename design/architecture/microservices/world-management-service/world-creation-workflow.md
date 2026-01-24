# World Creation Workflow

World creation is a long-running process that prepares the initial world state for a new **game instance** using already-published world data for a given `tenantId`. The workflow uses the shared **Saga** utilities from `firemud-common` so each step can be rolled back if another step fails. `WorldCreationService` is invoked when the platform provisions a new game instance for an existing tenant, typically from the Game Session Service. The identifiers involved are:

- `tenantId` – identifies the game (tenant) as described in
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- `versionId` – identifies the published world/template data to use, as described in
  [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `gameInstanceId` – identifies the specific running world instance recorded in
  the Game Session Service.

The implementation uses the published world topology for the chosen `tenantId` and `version_id`, inserts a starter region instance, schedules initial events, and can generate terrain chunks and spawn default NPCs. Additional stages run for large games that require deeper world seeding. Throughout the workflow, the Saga:

- Reads only **template/topology** rows keyed by `(tenantId, versionId)` (for example `region_template`, `room_template`, or authored generation metadata); and
- Writes only **instance** rows keyed by `(tenantId, gameInstanceId)` (for example `region_instance`, `room_instance`, `world_event`).

It never mutates template rows for Published versions; any structural changes to the world layout must occur through design-time workflows on Draft versions before publishing a new `versionId`.

## Steps

1. **Create Starter Region Instance** – uses the published world topology for the chosen `tenantId` and `version_id` and inserts initial regions or instances using the local shard configuration (`WORLD_LOCAL_SHARD_ID`). Default `SimpleDungeonGenerator` parameters populate the initial "Starter Region" as instance records associated with `gameInstanceId`; the underlying template graph remains unchanged.
2. **Schedule Initial Events** – inserts world events such as an initial weather state so `WorldEventService` can apply them after the world starts.
3. **Generate Terrain & Spawn NPCs** – optional stages that create terrain chunks and seed default NPC populations for expansive worlds.

```java
SagaBuilder builder = new SagaBuilder("createWorld");
builder
    .step("createStarterRegion", () -> createStarterRegionInstance(tenantId, versionId))
    .step("scheduleEvents", () -> scheduleInitialEvents(tenantId));
sagaRunner.run(builder.build());
```

The saga state is stored in the `saga_instance` and `saga_step` tables defined in the common library. Operators can inspect progress through the Logging & Admin Service's saga dashboard.

See [World Management Service](README.md) for additional service context.

See [Transaction Strategies](../../system-architecture-transactions.md) for background on how sagas are used across FireMUD.
