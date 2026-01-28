# Microservices Responsibility Matrix

| Function | Game Design Service | World Management Service | Account Service | Game Session Service | Entity Management Service | Game Logic Service | Automation & Scripting Service | Social & Groups Service | Logging & Admin Service | TCP Proxy Service | Spring Cloud Gateway |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Game configuration authoring | ✔ | | | | | | | | | | |
| Custom in-game scripting authoring | ✔ | | | | | | | | | | |
| Game version publishing | ✔ | | | | | | | | | | |
| Design-time feature flag definitions | ✔ | | | | | | | | | | |
| Room and zone editing | ✔ | | | | | | | | | | |
| World map region layout | | ✔ | | | | | | | | | |
| Room topology and static metadata (descriptions, flags, ambient properties) | | ✔ | | | | | | | | | |
| Room dynamic world state (persistent environment flags, doors, hazards) | | | | | ✔ | | | | | | |
| Room occupancy (entity locations in rooms) | | | | | ✔ | | | | | | |
| Pathfinding algorithms and navmesh data | | ✔ | | | | | | | | | |
| Account authentication, credential verification, and JWT issuance (JWKS) | | | ✔ | | | | | | | | |
| Account-related email (verification, password reset, security alerts, subscription/billing notifications) | | | ✔ | | | | | | | | |
| Operational and moderation notifications (alerts, moderation actions, admin digests) | | | | | | | | | ✔ | | |
| Payment, subscriptions, and bans | | | ✔ | | | | | | | | |
| Account security policy (password rules, lockout, MFA requirements) | | | ✔ | | | | | | | | |
| Gameplay login command handling and session binding (Redis) | | | | ✔ | | | | | | | |
| Login throttling, lockout, password reset, and email verification | | | ✔ | | | | | | | | |
| WebSocket transport connection lifecycle (upgrade, routing, DMZ edges) | | | | | | | | | | ✔ | ✔ |
| Gameplay session lifecycle (login, resume, takeover) | | | | ✔ | | | | | | | |
| Reconnection handling (resume gameplay) | | | | ✔ | | | | | | | |
| Command queuing and dispatch | | | | ✔ | | | | | | | |
| Session state storage (volatile, Redis gameplay bindings) | | | | ✔ | | | | | | | |
| Coordination Redis ownership (ticks, locks, timers, sessions) | | | | ✔ | | | | | | | |
| Game version activation at runtime | | | | ✔ | | | | | | | |
| Runtime feature flag overrides | | | | ✔ | | | | | | | |
| Tick & coordination health metrics (per region) | | | | ✔ | | | | | | | |
| Entity definition and persistence | | | | | ✔ | | | | | | |
| NPC state, inventory, and stats | | | | | ✔ | | | | | | |
| Player inventory and stats | | | | | ✔ | | | | | | |
| Item definitions and crafting data | | | | | ✔ | | | | | | |
| Game mechanics and combat resolution | | | | | | ✔ | | | | | |
| Command parsing and alias resolution | | | | | | ✔ | | | | | |
| Action execution (movement, attack, etc.) | | | | | | ✔ | | | | | |
| Progression logic (XP, levels, effects) | | | | | | ✔ | | | | | |
| Environmental effects (weather, etc.) | | | | | | ✔ | | | | | |
| Economy logic (trading, shops, pricing) | | | | | | ✔ | | | | | |
| AI-driven actions and behaviors | | | | | | | ✔ | | | | |
| Triggered script execution | | | | | | | ✔ | | | | |
| Redis-backed automation tick coordination (`automation:*` keys) | | | | | | | ✔ | | | | |
| Coordination Redis participation via shared helpers (locks, automation tick prefixes) | | | | ✔ | ✔ | | ✔ | | | | |
| Cache/Rate-Limit Redis usage (caches, quotas, rate limiting) | | | | | ✔ | | ✔ | ✔ | | ✔ | ✔ |
| Chat and private messaging | | | | | | | | ✔ | | | |
| Guilds and group discovery | | | | | | | | ✔ | | | |
| Social network graph (friends/blocks/etc.) | | | | | | | | ✔ | | | |
| Centralized observability dashboards and moderation analytics (logs/metrics/traces) | | | | | | | | | ✔ | | |
| Admin panel and feature flag toggling | | | | | | | | | ✔ | | |
| Game moderation tools | | | | | | | | | ✔ | | |
| Game moderation policy definition | | | | | | | | | ✔ | | |
| Automated tick/coordination remediation (pause/resume/reset) | | | | | | | | | ✔ | | |
| Game asset publishing & object storage | ✔ | | | | | | | | | | |
| TCP/Telnet socket handling | | | | | | | | | | ✔ | |
| Telnet → WebSocket bridging | | | | | | | | | | ✔ | |
| WebSocket upgrade, routing, and admin auth gating | | | | | | | | | | | ✔ |
| Dynamic route management and gateway configuration | | | | | | | | | | | ✔ |
| API gateway rate limiting and abuse filters | | | | | | | | | | | ✔ |

## Notes on Redis Ownership and Participation

- **Coordination Redis ownership (ticks, locks, timers, sessions)** – Game Session Service owns the coordination keyspace and schema (for example, `coord:*`, `tick:*`, and `session:*` prefixes). Other services participate in coordination via shared helper libraries and documented key formats; they do not introduce new coordination prefixes or modify TTLs without going through Game Session ownership and the Redis design review process.
- **Redis-backed automation tick coordination (`automation:*` keys)** – Automation & Scripting Service owns the `automation:*` keyspace and Lua scripts that drive automation ticks. Game Session and other services interact with automation via gRPC APIs, not by writing `automation:*` keys directly.
- **Cache/Rate-Limit Redis usage (caches, quotas, rate limiting)** – TCP Proxy Service, Spring Cloud Gateway, Entity Management Service, Automation & Scripting Service, and Social & Groups Service all use shared cache and rate‑limit helpers backed by Redis (for example, `cache:*` and `ratelimit:*` prefixes). The schema, TTL policies, and correctness guarantees for these prefixes are defined in the shared cache/rate-limit library and in the Redis Cache & Rate Limiting design; individual services should not diverge from these patterns.

## Related Documentation

- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
