# Game Session Service

## Overview

Orchestrates live game sessions, including tick execution, player input validation, and runtime feature toggles. Acts as the central hub for gameplay state.

## Architecture / Design Notes

- Coordinates with Redis to store volatile session state and command queues.
- Communicates with other microservices exclusively via gRPC.
- Communicates game lifecycle changes to other services via gRPC so they can react to games starting or ending.

## Key Features

- **Session Lifecycle Management** — creates, resumes, and terminates player sessions.
- **Tick Orchestration** — drives the hybrid tick model for deterministic action processing.
- **Runtime Configuration** — activates published game versions and feature flags.
- **Termination Handling** — cleans up resources and logs results when a game ends.
- **Instance Initialization** — starts new games from published templates.

## Dependencies

- **Internal:** Entity Management Service, Game Logic Service, World Management Service.
- **External:** Redis for session state.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md), [**Deployment Environments**](../../infrastructure/deployment-environments.md), and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for details on shared infrastructure components.

## Related Docs

See [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md) for how game instances load published versions and runtime flags.

## Future Enhancements

- Cross-region sharding for massive worlds.
- Built-in analytics for player behavior.
