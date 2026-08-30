# Automation & Scripting Service

## Overview

The Automation & Scripting Service drives non-player character (NPC) behavior and world automation. It executes custom scripts and AI routines so worlds stay alive even when no players are online.

### Responsibilities

- Executes sandboxed scripts triggered by world and player events.
- Provides backend APIs and a sandboxed engine for the visual DSL editor in the Game Design Service.
- Stores persistent NPC memory and automation queues.
- Integrates with Game Session and World Management services for real-time updates.

For details on how scripts are authored, how standard and custom events are modeled, and how they execute safely, see:

- [System Architecture: Scripting & Automation](../../system-architecture-scripting.md#tldr-flow) for the high-level flow and service interactions.
- [Scripting DSL Reference & Event Lifecycle](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#supported-script-events) for event types and lifecycle.
- [Custom and Service-Specific Events](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#custom-and-service-specific-events) for how non-standard events are versioned and ordered.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

## Script-transition boundary

Automation & Scripting owns tenant readiness, immutable compiled artifacts, and an instance-scoped observed pin projection for local admission and diagnostics. **Target state:** during a temporary Game Session owner-read outage, a bounded-fresh observed projection may satisfy instance-scoped admission with exact `(scriptPatchVersion, scriptPinEpoch)` evidence. If the projection is absent or stale, a bounded owner refresh must succeed or admission fails closed. The service never uses stale state to admit work, commits a pin, or creates rollout history. Tenant-readiness `onLoad` remains pre-instance-pin work identified by the candidate `scriptPatchVersion` and readiness identity; it carries no `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `scriptPinEpoch`, or `entityId`, and creates no gameplay work. Local consequences follow the [scripting contracts](../../system-architecture-scripting-contracts.md), [control-plane API](../../system-architecture-scripting-control-plane-api.md), and [rollout and rollback](../../system-architecture-scripting-rollout-and-rollback.md) owners, with lifecycle boundaries recorded in [ADR 0103](../../decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), [ADR 0106](../../decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md), [ADR 0107](../../decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), [ADR 0108](../../decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), [ADR 0109](../../decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](../../decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), and [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md).

**Target state:** the local admission consequence is exact-artifact loading against the observed owner tuple; missing, stale, or mismatched evidence fails closed without a local fallback. Scoped Automation admission and cleanup participate through the owner APIs while ordinary gameplay remains outside this service-local admission barrier.

Automation's **target-state only** contract exposes stage-aware dead-letter recovery, schedule/timer, and plugin lifecycle consequences through its local APIs; the authority, recovery, continuity, and embedded-versus-plugin lifecycle rules remain in the canonical owners linked above.

## Implementation Status

The current `ReplayDeadLetteredWorkItems` implementation still requeues eligible parent rows as `PENDING_EVALUATION` and returns aggregate counts; it does not prove stage-specific frozen-input retry or stored-output continuation.

The current Automation pin projection and wire contract remain patch-only, so exact-epoch admission, exact `scriptPinEpoch` propagation/lookup, same-version epoch-only `REPIN`, and proof at this boundary remain target-only/incomplete.

## Key Features

- Scriptable quests and event triggers.
- Persistent NPC memory and dynamic reactions.
- Timers and delayed actions for asynchronous events.
- Script evaluation occurs outside the tick system. Results are queued as commands that run during tick cycles, ensuring fair scheduling without blocking gameplay.
- Faction reputation influences NPC aggression states. NPCs may become `FLEEING` or `SURRENDERED` when low on health or morale, allowing players to resolve encounters non-lethally.
- Web UI for creating and testing scripts using a component-based DSL.
- Advanced AI modules support formations, squads, and complex behaviors.
- Bounded procedural encounter generation is present: the current population hook invokes seeded encounter generation, but its result is discarded; it does not spawn or persist NPCs/loot or emit tick-driven commands. Full World-owned generation and tick handoff remain target work; see [Automation and Scheduler Runtime tracker](../../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#active-gaps). Scripts do not persist world topology or directly mutate World Management instance rows.
- `ScriptQuotaService` enforces fairness quotas and per-script resource limits.

## Document Map

- [API Contracts](./api-contracts.md)
  - event ingress, control/read APIs, and wire-level ownership for automation-facing contracts.
- [Runtime and Data](./runtime-and-data.md)
  - Redis/PostgreSQL ownership, queue/outbox boundaries, quota state, and runtime invariants.
- [Operations](./operations.md)
  - readiness/liveness, reload and rollback behavior, and operator-facing runtime guidance.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and configuration source locations.
- [Script Sandbox & Resource Limits](./sandbox-runtime-design.md)
  - sandbox execution model, isolation, and detailed resource-limit rules.

## Dependencies

- **Internal:**
  - Game Session Service sends events that trigger scripts.
  - Game Logic Service supports rule evaluation.
  - World Management Service receives world-state update commands from scripts (for example door/weather toggles) via tick-driven effects scoped by `RoomInstanceRef` and guarded by `EffectId`.
- **External:**
  - PostgreSQL for script storage and durable automation work items.
  - Redis for automation coordination, queue indexes, and quota counters.

See [Gateway Architecture](../../system-architecture-gateway.md), [Deployment Environments](../../infrastructure/deployment-environments.md), and [Protocol Bridging](../../system-architecture-protocol-bridging.md) for details on shared infrastructure components.

## Related Documentation

- [Script Sandbox & Resource Limits](./sandbox-runtime-design.md)
- [System Architecture: Scripting & Automation](../../system-architecture-scripting.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Add Automation & Scripting](../../../product/user-journeys/creators.md#3-add-automation--scripting)
- [System Architecture Overview](../../system-architecture-overview.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
