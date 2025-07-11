# Automation & Scripting Service

## Overview

The Automation & Scripting Service drives non-player character (NPC) behavior and world automation. It executes custom scripts and AI routines so worlds stay alive even when no players are online.

### Responsibilities

- Executes sandboxed scripts triggered by world and player events
- Provides a visual DSL for designers to build behaviors
- Stores persistent NPC memory and automation queues
- Integrates with Game Session and World Management services for real-time updates

For details on how scripts are authored and executed safely, see [System Architecture: Scripting & Automation](../system-architecture-scripting.md).

## Architecture / Design Notes

- Executes scripts in response to world or player events received via gRPC callbacks.
- Scripts run inside a sandboxed engine to prevent malicious behavior.
- Scripts are authored in a **component-based DSL** using a visual editor so
  designers can build behaviors without coding.
- AI computations are optimized for large worlds using tick-based batching.
- Script definitions are versioned and can be hot reloaded without downtime as
  described in [System Architecture: Scripting & Automation](../system-architecture-scripting.md).
- Uploading or replacing scripts is handled as a Saga workflow so that failures
  can be rolled back. See [Transaction Strategies](../system-architecture-transactions.md).
- Each game's scripts live in tables keyed by `tenantId`, ensuring automation for
  one game cannot access another's data. Redis queues also include the tenant
  prefix; see [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Scriptable quests and event triggers  
- Persistent NPC memory and dynamic reactions  
- Timers and delayed actions for asynchronous events  
- Tick-based AI execution for efficient CPU usage and fair scheduling — AI logic runs during tick cycles only when triggered by events, avoiding constant background processing
- Faction reputation influences NPC aggression states. NPCs may become **FLEEING** or **SURRENDERED** when low on health or morale, allowing players to resolve encounters non-lethally.

### Data Model

- `script` table holds the compiled component definitions and version metadata.
- `npc_memory` table stores persistent state for NPC behaviors.
- `automation_queue` keys in Redis buffer triggered events until a script runs.
- `automation_queue_enqueued_total` and `automation_queue_drained_total` metrics
  track Redis queue activity.
- Player reputation data is stored in the Social & Groups Service; see its
  [data model](../social-groups-service/README.md#data-model) for the
  `faction` and `faction_standing` tables.

### Script Lifecycle

- Scripts reside in the Automation & Scripting Service database and are versioned along with other game data as described in the design service versioning process.
- Events from the Game Session Service trigger script execution via gRPC.
- The sandboxed engine limits CPU time and memory for each script to prevent
  runaway behavior.

### gRPC APIs

- `TriggerEvent` – informs the service of an in-game event so matching scripts
  can run.
- `UpdateScript` – uploads or replaces a script definition for later use.
- `GetScriptStatus` – queries whether a script is queued or running for a given
  entity.

## Faction & Reputation System

NPC behaviour references player reputation to decide when to become hostile,
flee, or surrender. These reputation scores are maintained by the Social &
Groups Service. See the
[Social & Groups Service](../social-groups-service/README.md#data-model) for the
`faction` and `faction_standing` tables that store reputation data.

## Dependencies

- **Internal:**
  - Game Session Service sends events that trigger scripts.
  - Game Logic Service for rule evaluation.
  - World Management Service receives world-state updates from scripts.
- **External:** PostgreSQL for script storage and Redis for queuing automation tasks.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Proto Files

API definitions are located in
[../../../../protos/automation-scripting/v1](../../../../protos/automation-scripting/v1).
Run `./gradlew generateProto` after modifying these schemas to update the gRPC
stubs.

## 📚 Related Documentation

- [System Architecture: Scripting & Automation](../system-architecture-scripting.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Redis Architecture](../system-architecture-redis.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Add Automation & Scripting](../user-journeys.md#3-add-automation--scripting)
- [System Architecture Overview](../system-architecture-overview.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Additional Details

### Configuration

PostgreSQL and Redis connections are configured via the common `DatabaseAutoConfiguration` and `RedisProperties` classes. Refer to [Deployment Environments](../../infrastructure/deployment-environments.md) for default values. Local development typically uses the settings from `.env.sample`.

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`automation_scripting_service.proto`](../../../protos/automation-scripting/v1/automation_scripting_service.proto).

```bash
grpcurl -plaintext localhost:6565 automation_scripting.v1.AutomationScriptingService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Web UI for creating and testing scripts.
- Additional AI modules for advanced behaviors.
- Procedural world generation hooks working in tandem with the World Management Service. The initial implementation uses a lightweight dungeon generator described in [System Architecture: Procedural Generation](../system-architecture-procedural-generation.md).
- NPC fleeing and surrender logic.
- NPC formations and squad AI for coordinated encounters.
- Fairness quotas and per-script resource limits to prevent abuse, as outlined
  in [System Architecture: Scripting & Automation](../system-architecture-scripting.md#fairness--abuse-prevention-planned).
