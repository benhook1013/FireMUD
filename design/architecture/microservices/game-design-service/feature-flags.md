# Runtime Feature Flags

Runtime feature flags are a target-state mechanism for enabling or disabling optional behaviour without publishing a new game version. The **Game Design Service** is the source of truth for flag definitions; under the target behavior, the **Game Session Service** applies flag values during play.

## Implementation Status

Logging & Admin has no separate live feature-flag UI or forwarding path: `/feature-flags/toggle` fails closed with `503 Service Unavailable`, and its internal toggle entrypoint returns application-level `UNAVAILABLE` without dispatch. Game Session owns the current toggle mutation and canonical `feature_flag` persistence, but proof covers only the toggle/persistence/read seam; richer runtime application and broader consumer coverage remain unimplemented and unproved.

## Design-Time Definitions

- Designers create flag definitions in the Game Design Service UI.
- Definitions are stored per tenant in a `runtime_flag` table with metadata such as key, description and default state.
- When a version is published, the defined flags are copied into the Game Session Service so running games know which flags exist.

## Runtime Toggling and Persistence

- **Target state:** Administrators toggle flag values through the target [Logging & Admin Service](../logging-admin-service/README.md) operator surface, which forwards a gated owner request to the Game Session Service.
- **Target state:** The Game Session Service owns runtime flag state, persists active values in its `feature_flag` table, and reapplies them before each tick cycle to keep world behaviour consistent across ticks.
- Under the target owner operation, an accepted flag change takes effect at the next tick boundary for active sessions, not retroactively in the current tick. Reapplying the value before each tick keeps world behaviour consistent while allowing safe experimentation with mechanics, layout tweaks or pacing options.

## Customization Examples

Feature flags can control optional UI layout tweaks, status effect experiments or other per-tenant runtime settings. Additional pacing options like tick intervals can be stored alongside flag values for further customization.

## Related Documentation

- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
- [Game Customization](../../system-architecture-game-customization.md)
- [Game Session Service](../game-session-service/README.md)
- [Logging & Admin Service](../logging-admin-service/README.md)
