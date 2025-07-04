# Game Logic Service

## Overview

Executes the core gameplay rules and command parsing. It processes player actions and determines outcomes.

## Architecture / Design Notes

- Stateless service accessed over gRPC by other microservices.
- Uses a modular command parser for extensibility.
- Deterministic rule execution; random seeds come from the Game Session Service.
- Fetches contextual world and entity data on demand via gRPC.

## Key Features

- Command parsing and alias system.
- Rule processing for combat and progression.
- Emote and roleplay action handling.
- Effect stacking and cooldown calculation.

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

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)

## Future Enhancements

- Scripting hooks for custom actions.
- Performance optimizations for large-scale battles.
