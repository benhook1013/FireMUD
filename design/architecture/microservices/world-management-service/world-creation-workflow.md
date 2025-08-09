# World Creation Workflow

World creation is a long running process that will eventually copy design data
and prepare the initial world state for a new game instance.
The workflow uses the shared **Saga** utilities from `firemud-common` so each step can be rolled
back if another step fails. `WorldCreationService` is invoked when a tenant
launches a new game world, typically from the Game Session Service.

Currently the implementation only inserts a starter region. The method that
would schedule placeholder events is a stub and does not yet create records.

, and additional steps, such as bulk terrain generation
or NPC population, remain optional.

## Steps

1. **Copy Design Data & Create Starter Region** – fetches the published version
   from the Game Design Service and inserts an initial region using the local
   shard configuration (`WORLD_LOCAL_SHARD_ID`). The current code merely verifies
   connectivity with the Game Design Service and inserts a single "Starter
   Region" record populated with default `SimpleDungeonGenerator` parameters.
   Additional regions and a true data copy will follow once more design data is
   available.
2. **Schedule Initial Events** – intended to insert world events such as an
   initial weather state so `WorldEventService` can apply them after the world
   starts. This step is currently a stub and does not persist any events
  .

Additional steps may be added for large games
such as generating terrain chunks or spawning default NPCs.

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

The saga state is stored in the `saga_instance` and `saga_step` tables defined
in the common library. Operators can inspect progress through the Logging &
Admin Service's saga dashboard.

See [World Management Service](README.md) for additional service context.

See [Transaction Strategies](../system-architecture-transactions.md) for
background on how sagas are used across FireMUD.
