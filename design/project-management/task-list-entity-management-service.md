# Entity Management Service Task List

## Entity Storage

- [x] Implement player character storage
- [x] Implement NPC storage and data structures
- [x] Implement item and inventory management
- [x] Implement entity stats and progression tracking
- [x] Implement NPC respawn rules and timing

## Shared Account & Crafting

- [x] Implement cross-game account linking (allow single account across multiple hosted games)
- [x] Support complex crafting recipes
- [ ] Harden recipe management APIs with additional validation
- [ ] Expand integration tests for cross-game character listing and recipe workflows

## Performance & Data Sync

- [x] Implement entity graph caching for fast lookups
- [ ] Copy published version data into entity schema via Saga
- [ ] Implement `character-cache:<tenantId>:<characterId>` as a Class A, versioned cache using the patterns in `system-architecture-redis-cache.md` (version field, version-checked reads, atomic set+TTL writes).
- [ ] Implement `inventory:<tenantId>:<containerId>` cache as described in the worked example in `system-architecture-redis-cache.md` (authoritative version from PostgreSQL, version-checked reads, event-driven invalidation, reset-tolerant lazy repopulation).
- [ ] Add cache metrics and tests for character and inventory caches (hit/miss counters, key-count gauges, oversize guards) consistent with the Redis operations and cache testing sections.

## Security & Operations

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.
