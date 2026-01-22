# Microservices Responsibility Matrix

| Function | Game Design Service | World Management Service | Account Service | Game Session Service | Entity Management Service | Game Logic Service | Automation & Scripting Service | Social & Groups Service | Logging & Admin Service | TCP Proxy Service | Spring Cloud Gateway |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Game configuration authoring | ✔ | | | | | | | | | | |
| Custom in-game scripting authoring | ✔ | | | | | | | | | | |
| Game version publishing | ✔ | | | | | | | | | | |
| Design-time feature flag definitions | ✔ | | | | | | | | | | |
| Room and zone editing | ✔ | | | | | | | | | | |
| World map region layout | | ✔ | | | | | | | | | |
| Room state persistence | | ✔ | | | | | | | | | |
| Pathfinding algorithms and navmesh data | | ✔ | | | | | | | | | |
| Account authentication, credential verification, and JWT issuance (JWKS) | | | ✔ | | | | | | | | |
| Email and system notifications | | | ✔ | | | | | | ✔ | | |
| Payment, subscriptions, and bans | | | ✔ | | | | | | | | |
| Gameplay login command handling and session binding (Redis) | | | | ✔ | | | | | | | |
| WebSocket transport connection lifecycle (upgrade, routing, DMZ edges) | | | | | | | | | | ✔ | ✔ |
| Gameplay session lifecycle (login, resume, takeover) | | | | ✔ | | | | | | | |
| Reconnection handling (resume gameplay) | | | | ✔ | | | | | | | |
| Command queuing and dispatch | | | | ✔ | | | | | | | |
| Session state storage (volatile, Redis) | | | | ✔ | | | | | | | |
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
| Logging and metrics collection | | | | | | | | | ✔ | | |
| Admin panel and feature flag toggling | | | | | | | | | ✔ | | |
| Game moderation tools | | | | | | | | | ✔ | | |
| Game moderation policy definition | | | | | | | | | ✔ | | |
| Automated tick/coordination remediation (pause/resume/reset) | | | | | | | | | ✔ | | |
| TCP/Telnet socket handling | | | | | | | | | | ✔ | |
| Telnet → WebSocket bridging | | | | | | | | | | ✔ | |
| WebSocket upgrade, routing, and admin auth gating | | | | | | | | | | | ✔ |
| Dynamic route management and gateway configuration | | | | | | | | | | | ✔ |
| API gateway rate limiting and abuse filters | | | | | | | | | | | ✔ |

## Related Documentation

- [Microservices Overview](./microservices/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
