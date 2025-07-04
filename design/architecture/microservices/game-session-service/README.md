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

## Dependencies

- **Internal:** Entity Management Service, Game Logic Service, World Management Service.
- **External:** Redis for session state.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md), [**Deployment Environments**](../../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for details on shared infrastructure components.

## 📚 Related Documentation

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how game instances load published versions and runtime flags.

## Future Enhancements

- Cross-region sharding for massive worlds.
- Built-in analytics for player behavior.
