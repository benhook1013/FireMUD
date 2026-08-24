# Game Logic Service

## Overview

Executes the core gameplay rules and command parsing. It processes player actions and determines outcomes, while Game Session owns queueing, tick context, and final client delivery.

Game Logic is intentionally a replaceable same-type worker rather than a keeper of authoritative process-local gameplay state. If one Game Logic instance disappears, another instance should be able to continue serving requests from shared authoritative inputs and durable effect/idempotency records without making that restart itself a player-visible event.

## Implementation Status

- Live: the data-driven `LOOK` path is wired into the command pipeline via `ResolveLook`, and `SendCommunication` forwards normalized gameplay `say`/`whisper`/`tell` payloads to the Social & Groups stub while returning recipient metadata that lets Game Session render canonical actor and room-listener prose. This is the gameplay class only, not a universal communication ingress.
- Stubbed: room and entity context still comes from the deterministic LOOK fixtures, and chat delivery still uses the regression Social & Groups stub so canonical transcripts remain deterministic.
- Deferred: richer LOOK prose, combat and effect annotations, NPC reply behavior, published closed observer-view declarations, authored partial-observation mechanics, profile-defined bounded `SHOUT`, and profanity-escalation flows remain future slices. No area/region `SHOUT` policy is implied.

## Responsibilities

- Parse player commands and resolve actions
- Apply combat rules, cooldowns, and environmental effects
- Compute movement/travel costs and pathfinding using world geometry
- Interact with entity and world services for context data
- Push results back to the Game Session Service for distribution
- Resolve world/gameplay communication semantics (topology, perception, capabilities, effects, authored interception, and bounded candidate views) for the communication classes that depend on gameplay state; send one bounded gameplay plan to Social & Groups first for moderation/history/social-audience and social-channel delivery-state/fanout decisions, then return the authorized presentation result to Game Session for final connected-gameplay transport delivery
- See the [Service Responsibility Matrix](../../service-responsibility-matrix.md)
  for how this service fits into the overall architecture.
- Fail readiness when the downstream dependencies required for the currently exposed gameplay command path are unavailable

## Key Features

- Command parsing and alias system.
- Rule processing for combat and progression.
- Emote and roleplay action handling.
- Parse the live gameplay `SAY`, nearby `WHISPER`, and gameplay `TELL` commands. Target state extends that parser to any other published gameplay communication type. Account messaging, ordinary guild/group channels, browser social actions, and ordinary account/social mail enter Social & Groups directly; an in-game adapter does not reclassify them as gameplay or expose their private content to tenant-authored scripts.
- Event dispatcher for triggers and world events.
- Effect stacking and cooldown calculation.
- Environmental effect resolution (weather, lighting) influencing gameplay.
- Economy logic for trading, shops, and pricing adjustments.
- Procedural generation commands such as generate-dungeon are executed in
  solo ticks, coordinated by the Game Session Service and handled by the
  Automation & Scripting Service to avoid impacting other players.
- Scripting hooks let creators inject custom actions into the command engine.
- Optimized rule evaluation supports large-scale battles.

## Document Map

- [API Contracts](./api-contracts.md)
  - gameplay-facing REST/gRPC surfaces, `LOOK` and `SendCommunication` contract ownership, and source-of-truth pointers.
- [Runtime and Data](./runtime-and-data.md)
  - runtime rule ownership, Redis-role boundaries, publish-gating/digest rules, and gameplay-state invariants.
- [Operations](./operations.md)
  - readiness/liveness, runtime slice status, and operator-facing behavior.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and service-local configuration source locations.

## Dependencies

- **Internal:**
  - Entity Management Service for characters and items.
  - World Management Service for room and region data.
  - Game Session Service supplies tick context and command queues.
  - Automation & Scripting Service triggers additional effects during rule execution.
  - Social & Groups Service owns social-channel delivery, relationships/groups, applicable history, moderation boundaries, and ordinary account/social mail; deliberately world-specific mail remains a gameplay communication class under Game Logic, while Social still owns its delivery envelope and Entity owns any value. Social does not own world topology, connected gameplay transports, or Entity value.

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [User Journeys – Player Login and Gameplay](../../../product/user-journeys/players.md#4-player-login-and-gameplay)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
