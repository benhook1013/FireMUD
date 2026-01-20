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

## Security & Operations

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise. For the shared checklist, see `design/project-management/reusable-microservice-checklist.md`.
