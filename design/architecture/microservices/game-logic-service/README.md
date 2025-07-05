# Game Logic Service

## Overview

Executes the core gameplay rules and command parsing. It processes player actions and determines outcomes.

## Architecture / Design Notes

- Stateless service accessed over gRPC by other microservices.
- Uses a modular command parser for extensibility.
- Deterministic rule execution; random seeds come from the Game Session Service.
- Fetches contextual world and entity data on demand via gRPC.
- Integrates with the tick system described in [Tick System and Runtime Design](../system-architecture-ticks.md) to ensure deterministic command ordering.
- When combat or trade spans multiple services, compensating actions are coordinated via the Saga model in [Transaction Strategies](../system-architecture-transactions.md).

## Key Features

- Command parsing and alias system.
- Rule processing for combat and progression.
- Emote and roleplay action handling.
- Effect stacking and cooldown calculation.
- Environmental effect resolution (weather, lighting) influencing gameplay.
- Economy logic for trading, shops, and pricing adjustments.

### Data Model

This service is largely stateless. It relies on:
- Contextual entity and world data fetched from other services via gRPC.
- Temporary command queues stored in Redis by the Game Session Service.

### Command Flow

1. Commands are queued in Redis by the Game Session Service.
2. This service fetches the next command, loads the required context, and
   resolves the action to a rule engine module.
3. Results are pushed back to the session queue for delivery to players.

### gRPC APIs

- `ExecuteCommand` – evaluates a parsed command and returns the outcome.

## Dependencies

- **Internal:** Entity Management Service for characters and items.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

gRPC service definitions can be found in
[../../../../protos/game-logic/v1](../../../../protos/game-logic/v1). Rebuild
the generated code with `./gradlew generateProto` after making changes.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Redis Architecture](../system-architecture-redis.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)

## Future Enhancements

- Scripting hooks for custom actions.
- Performance optimizations for large-scale battles.
