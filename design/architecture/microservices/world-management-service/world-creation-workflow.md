# World Creation Workflow

World creation is a long-running process that copies design data and prepares the initial world state for a new game instance. The workflow uses the shared **Saga** utilities from `firemud-common` so each step can be rolled back if another step fails. `WorldCreationService` is invoked when a tenant launches a new game world, typically from the Game Session Service.

The implementation copies published design data, inserts a starter region, schedules initial events, and can generate terrain chunks and spawn default NPCs. Additional stages run for large games that require deeper world seeding.

## Steps

1. **Copy Design Data & Create Starter Region** – fetches the published version from the Game Design Service and inserts initial regions using the local shard configuration (`WORLD_LOCAL_SHARD_ID`). Default `SimpleDungeonGenerator` parameters populate the initial "Starter Region".
2. **Schedule Initial Events** – inserts world events such as an initial weather state so `WorldEventService` can apply them after the world starts.
3. **Generate Terrain & Spawn NPCs** – optional stages that create terrain chunks and seed default NPC populations for expansive worlds.

```java
SagaBuilder builder = new SagaBuilder("createWorld");
builder
    .step(
        "copyDesign",
        () -> copyDesignData(tenantId, versionId),
        () -> rollbackDesignCopy(tenantId))
    .step("scheduleEvents", () -> scheduleInitialEvents(tenantId));
sagaRunner.run(builder.build());
```

The saga state is stored in the `saga_instance` and `saga_step` tables defined in the common library. Operators can inspect progress through the Logging & Admin Service's saga dashboard.

See [World Management Service](README.md) for additional service context.

See [Transaction Strategies](../../system-architecture-transactions.md) for background on how sagas are used across FireMUD.
