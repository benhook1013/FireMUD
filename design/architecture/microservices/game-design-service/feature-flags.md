# Runtime Feature Flags

Runtime feature flags let FireMUD enable or disable optional behaviour without publishing a new game version. The **Game Design Service** is the source of truth for flag definitions, while the **Game Session Service** applies flag values during play.

## Design-Time Definitions

- Designers create flag definitions in the Game Design Service UI.
- Definitions are stored per tenant in a `runtime_flag` table with metadata such as key, description and default state.
- When a version is published, the defined flags are copied into the Game Session Service so running games know which flags exist.

## Runtime Toggling and Persistence

- Administrators toggle flag values through the [Logging & Admin Service](../logging-admin-service/README.md), which forwards changes via gRPC to the Game Session Service.
- The Game Session Service persists active flag values in its `feature_flag` table and reapplies them before each tick cycle to keep world behaviour consistent across ticks.
- Flag changes take effect immediately for active sessions, allowing safe experimentation with mechanics, layout tweaks or pacing options.

## Customization Examples

Feature flags can control optional UI layout tweaks, status effect experiments or other per-tenant runtime settings. Additional pacing options like tick intervals can be stored alongside flag values for further customization.

## Related Documentation

- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
- [Game Customization](../../system-architecture-game-customization.md)
- [Game Session Service](../game-session-service/README.md)
- [Logging & Admin Service](../logging-admin-service/README.md)
