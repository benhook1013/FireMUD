# Game Session Service Task List

## Session Lifecycle

> **Note:** Login, session resumption, and reconnect details are now tracked in [design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md](vertical-slices/02-task-list-login-and-session-vertical-slice.md). Use that checklist for the active vertical slice instead of duplicating items here.

- [x] Implement game instance lifecycle (start, stop, restart)
- [x] Support multi-tenancy for hosted games
- [x] Persist session state in Redis for reconnect recovery
- [x] Enforce single-session control per character (session takeover on new login)
- [x] Plan for cross-region sharding and session handoff
- [ ] Implement cross-region sharding and session handoff
- [ ] Restore session state on reconnect, rebinding socket, region, timers, and in-flight actions
- [ ] Forward TOTP codes to the Account Service during login
- [ ] Refresh roles in-session when `scopedRoles` are updated
- [ ] Implement `LOGIN`/`LOGON` command handling for interactive and parameterized logins
- [ ] Forward JWTs to backend services on behalf of clients

## Tick Management

- [x] Implement tick orchestration using Redis for command queues
- [x] Implement Lua-based staging, commit, and rollback scripts for tick transactions
- [x] Implement distributed lock acquisition in Redis for tick updates
- [x] Implement tick replay and crash recovery logic
- [ ] Implement graceful degradation when Redis operations stall to avoid gameplay interruption
- [ ] Record conflict metadata during retries to highlight hotspots and enable adaptive throttling
- [ ] Support per-tenant tick intervals to customize pacing across games
- [ ] Implement stat-based prioritization for action execution
- [ ] Schedule entity updates for cooldowns, patrols, and regeneration
- [ ] Add backoff windows and retry caps for failed actions
- [ ] Add graph-based conflict resolution for repeated contention
- [ ] Batch database writes at the end of each tick
- [ ] Implement timer scanning and dynamic time scaling
- [ ] Implement session rebinding and deduplication using Redis keys
- [ ] Fan out global events across tick regions
- [ ] Implement cross-region command relay using `remote:<tenantId>:<entityId>` hint markers (durable follow-ups live in PostgreSQL)

## Caching & Redis Roles

- [ ] Implement `view:room-look:<tenantId>:<roomId>` as a Class B, TTL-only cache on Cache/Rate-Limit Redis (rendered LOOK views, short/bounded TTLs, no gameplay-critical decisions depending on cache correctness) as described in `system-architecture-redis-cache.md` and the Game Session service README.
- [ ] Add cache metrics for `view:room-look:*` (hit/miss counters, key-count gauge) and tests showing that cache loss/reset only triggers recomputation from authoritative world/entity services and does not affect game correctness.

## Analytics & Coordination

- [x] Manage runtime feature flags and expose toggle API via Logging & Admin Service ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
- [x] Implement `game_manifest` table for version coordination
- [ ] Restart active sessions when a new game version is published
- [ ] Apply script-only patches without restarting sessions
- [x] Emit gameplay analytics for operators
- [ ] Apply runtime feature flags during tick processing

## Security

- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Track login attempts per IP and temporarily blacklist repeated failures
- [ ] Send notification emails for suspicious login activity
- [ ] Detect command spam or abnormal tick patterns using abuse heuristics

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.
