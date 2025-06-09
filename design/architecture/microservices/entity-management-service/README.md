# Entity Management Service

## Overview

Handles player characters, NPCs, items, and inventory. Provides CRUD operations for entities and exposes them to other services.

## Architecture / Design Notes

- Uses JPA for persistence of entity data.
- Exposes gRPC endpoints for other microservices.

## Key Features

- Character and NPC management.
- Item storage and inventory handling.
- Experience and level tracking.

## Dependencies

- **External:** PostgreSQL for entity data.

## Future Enhancements

- Entity graph caching for faster lookups.
- Support for complex crafting recipes.
