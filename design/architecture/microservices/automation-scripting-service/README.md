# Automation & Scripting Service

## Overview

The Automation & Scripting Service drives non-player character (NPC) behavior and world automation. It executes custom scripts and AI routines so worlds stay alive even when no players are online.

For details on how scripts are authored and executed safely, see [System Architecture: Scripting & Automation](../system-architecture-scripting.md).

## Architecture / Design Notes

- Executes scripts in response to world or player events received via gRPC callbacks.
- Scripts run inside a sandboxed engine to prevent malicious behavior.
- AI computations are optimized for large worlds using tick-based batching.
- NPCs that are far from active players are deprioritized and only "wake up" on interaction.
- Script definitions are versioned and can be hot reloaded without downtime as
  described in [System Architecture: Scripting & Automation](../system-architecture-scripting.md).
- Uploading or replacing scripts is handled as a Saga workflow so that failures
  can be rolled back. See [Transaction Strategies](../system-architecture-transactions.md).

## Key Features

- Scriptable quests and event triggers.
- Persistent NPC memory and dynamic reactions.
- Timers and delayed actions for asynchronous events.
- On-demand AI execution to reduce CPU load.

### Data Model

- `script` table holds the compiled component definitions and version metadata.
- `npc_memory` table stores persistent state for NPC behaviors.
- `automation_queue` keys in Redis buffer triggered events until a script runs.

### Script Lifecycle

- Scripts reside in the Automation & Scripting Service database and are versioned independently from running game sessions.
- Events from the Game Session Service trigger script execution via gRPC.
- The sandboxed engine limits CPU time and memory for each script to prevent
  runaway behavior.

### gRPC APIs

- `TriggerEvent` – informs the service of an in-game event so matching scripts
  can run.
- `UpdateScript` – uploads or replaces a script definition for later use.
- `GetScriptStatus` – queries whether a script is queued or running for a given
  entity.

## Dependencies

- **Internal:** Game Logic Service for rule evaluation.
- **External:** PostgreSQL for script storage and Redis for queuing automation tasks.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Deployed as a Kubernetes Deployment with optional horizontal scaling.
- Exposes Spring Boot health endpoints for readiness and liveness probing.
- Prometheus and Fluent Bit collect metrics and logs for Elasticsearch analysis,
  with traces emitted via OpenTelemetry.
- Development under Docker Compose mirrors this setup using the same Spring
  profiles as described in
  [Deployment Environments](../../infrastructure/deployment-environments.md).

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
- [System Architecture Overview](../system-architecture-overview.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

## Future Enhancements

- Web UI for creating and testing scripts.
- Additional AI modules for advanced behaviors.
- Fairness quotas and per-script resource limits to prevent abuse, as outlined
  in [System Architecture: Scripting & Automation](../system-architecture-scripting.md#fairness--abuse-prevention-planned).
