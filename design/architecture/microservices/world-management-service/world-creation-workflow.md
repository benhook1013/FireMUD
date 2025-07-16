# World Creation Workflow

World creation is a long running process that copies design data and prepares the
initial world state for a new game instance. The workflow uses the shared
**Saga** utilities from `firemud-common` so each step can be rolled back if
another step fails. At the time of writing the service only inserts a starter
region and schedules placeholder events; full data copy from the Game Design
Service is still planned.

## Steps

1. **Copy Design Data & Create Starter Region** – fetches the published version
   from the Game Design Service and inserts an initial region using the local
   shard configuration. Additional regions will be added once more design data is
   available. (TODO: Not yet implemented)
2. **Schedule Initial Events** – inserts world events like weather initialization
   so the scheduler can apply them after the world starts. (TODO: Not yet
   implemented)

Additional steps may be added for large games
such as generating terrain chunks or spawning default NPCs.

```java
new SagaBuilder()
    .step(
        "copyDesign",
        () -> copyDesignData(tenantId, versionId),
        () -> rollbackDesignCopy(tenantId))
    .step("scheduleEvents", () -> scheduleInitialEvents(tenantId))
    .run();
```

The saga state is stored in the `saga_instance` and `saga_step` tables defined
in the common library. Operators can inspect progress through the Logging &
Admin Service's saga dashboard.

See [Transaction Strategies](../system-architecture-transactions.md) for
background on how sagas are used across FireMUD.
