# Game Session Service

## Overview

Orchestrates live game sessions, including tick execution, player input validation, and runtime feature toggles. Acts as the central hub for gameplay state.

## Architecture / Design Notes

- Coordinates with Redis to store volatile session state and command queues.
- Communicates with other microservices exclusively via gRPC.
- Communicates game lifecycle changes to other services via gRPC so they can react to games starting or ending.
- Provides a single point of truth for current tick and world time.
- Ensures atomic command execution using Redis transactions and Lua scripts.

## Key Features

- **Session Lifecycle Management** — creates, resumes, and terminates player sessions.
- **Tick Orchestration** — drives the hybrid tick model for deterministic action processing.
- **Runtime Configuration** — stores runtime flag values created in the Game Design Service and activates published game versions.
- **Termination Handling** — cleans up resources and logs results when a game ends.
- **Instance Initialization** — starts new games from published templates.
- **State Queries** — exposes gRPC methods to retrieve current game or player state for the web UI.

### Tick Execution Model

- Each session advances in fixed-length ticks controlled by a Redis-based timer.
- Commands are collected during a tick and executed in deterministic order.
- After execution, results are persisted and broadcast to connected clients.

### gRPC APIs

- `StartSession` – spins up a game instance from a published version.
- `EnqueueCommand` – adds a player action to the next tick's queue.
- `QueryState` – retrieves condensed session or player state for monitoring.

## Dependencies

- **Internal:** Entity Management Service, Game Logic Service, World Management Service.
- **External:** Redis for session state.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md), [**Deployment Environments**](../../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for details on shared infrastructure components.

## Proto Files

Service definitions reside in
[../../../../protos/game-session/v1](../../../../protos/game-session/v1). Run
`./gradlew generateProto` after modifying these files to regenerate stubs.

## 📚 Related Documentation

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how game instances load published versions and runtime flags.

## Future Enhancements

- Cross-region sharding for massive worlds.
- Built-in analytics for player behavior.
