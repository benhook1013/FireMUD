# Entity Management Service

## Overview

Handles player characters, NPCs, items, and inventory. Provides CRUD operations for entities and exposes them to other services.

## Architecture / Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.
- Caches frequently accessed character data in Redis for quick lookups.
- Applies optimistic locking to avoid conflicting updates on the same entity.

## Key Features

- Character and NPC management.
- Item storage and inventory handling.
- Experience and level tracking.
- Character creation templates pulled from the Game Design Service.

### Data Model

- `character` and `npc` tables share a base entity for stats and inventory slots.
- `item` table stores equipment, consumables, and quest objects.
- Many-to-many tables define inventory and equipment relationships.

### gRPC APIs

- `CreateCharacter` – builds a new player character from a template.
- `UpdateEntity` – updates stats or equipment for a character or NPC.
- `QueryInventory` – lists items for an entity with pagination.

## Dependencies

- **External:** PostgreSQL for entity data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Proto Files

Service interface definitions are stored in
[../../../../protos/entity/v1](../../../../protos/entity/v1). After editing the
proto files, run `./gradlew generateProto` to update generated sources.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)

## Future Enhancements

- Entity graph caching for faster lookups.
- Support for complex crafting recipes.
