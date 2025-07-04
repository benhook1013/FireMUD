# World Management Service

## Overview

The World Management Service stores and manages game world data such as rooms, regions, and maps. It persists world state beyond player sessions and handles scheduled world events, notifying other services over gRPC when the environment changes.

## Architecture / Design Notes

- World data is stored in PostgreSQL. Redis holds only transient active state used during gameplay.
- Changes are persisted incrementally to avoid heavy writes.
- Background tasks trigger scheduled world changes (daily resets or seasonal shifts) and notify relevant services via gRPC.
- Supports procedural generation with options for dynamic world expansion.

## Key Features

- Region and location management with shard support.
- Persistent world state with incremental saves.
- Procedural generation tools for rooms and terrain.
- Event scheduling for world-wide holidays or timed modifiers, communicating changes over gRPC.

## Dependencies

- **Internal:** Game Design Service for generation rules.
- **External:** PostgreSQL for world data, Redis for transient active state.

## Future Enhancements

- Tools for fine-tuning procedural generation rules.
- Support for multi-server world shards.
