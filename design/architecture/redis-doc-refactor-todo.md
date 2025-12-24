# Redis Docs Refactor – TODO

This file tracks the Redis documentation refactor so we can verify scope and ensure no information is lost. Original snapshots of the Redis docs live in `design/architecture/_redis-refactor-orig/`.

## Top-Level Goals

- Make `system-architecture-redis.md` a small conceptual hub.
- Move procedural/reset/runbook content into focused children.
- Introduce clear design checklists and usage/profile docs.
- Keep a baseline of original content for comparison at the end.

## Tasks

- [x] Snapshot original Redis docs under `design/architecture/_redis-refactor-orig/`  
  - Includes all `design/architecture/system-architecture-redis*.md` files.
- [x] Introduce a Redis cheat sheet as a front door  
  - `design/architecture/system-architecture-redis-cheatsheet.md`
- [x] Rescope `system-architecture-redis.md` into a concise hub  
  - Keep only mental model, tail-loss invariants, key naming/shard discipline, topology summary, and links to children.
  - Remove or move detailed reset, ops, and checklist sections into child docs.
- [x] Add `system-architecture-redis-usage-and-profiles.md`  
  - Describe Coordination vs Cache/Rate-Limit usage patterns, environment profiles, eviction/maxmemory guidance, and wiring via `FIREMUD_REDIS_COORD_*` / `FIREMUD_REDIS_CACHE_*`.
- [x] Add `system-architecture-redis-reset-and-recovery.md`  
  - Capture the full coordination reset model, reset vs repair vs accept-loss guidance, and narrative incident scenarios.
- [x] Add `system-architecture-redis-design-checklist.md`  
  - Consolidate detailed design checklists for: new/changed coordination prefixes, cache/rate-limit prefixes, Lua scripts, and operational behavior changes.
- [x] Align `system-architecture-redis-operations.md` to be runbook-only  
  - Ensure it contains concrete flows (AOF maintenance, resets, migrations) and delegates conceptual rationale to the hub + reset/checklist docs.
- [x] Ensure `system-architecture-redis-lua-patterns.md` is Lua-focused only  
  - Remove any environment/profile/reset details that belong in usage/ops/reset docs.
- [x] Review and update cross-references in related docs  
  - `system-architecture-redis-cache.md`, `system-architecture-redis-ops-access.md`, `system-architecture-testing.md`, and Redis-using microservice READMEs should point to the new usage/reset/checklist docs where appropriate.
- [ ] Final verification against originals  
  - Compare updated docs with `design/architecture/_redis-refactor-orig/` to confirm that information has been moved or summarized, not silently dropped.
