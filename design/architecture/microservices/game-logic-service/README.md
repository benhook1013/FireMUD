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
