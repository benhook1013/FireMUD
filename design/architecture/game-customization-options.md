# 🎮 Game Customization Options

This brief document summarizes the ways a hosted game can change its look and feel without modifying FireMUD source code.

## Theme and Branding

- Each tenant provides a Material‑UI theme file that overrides default colors and fonts.
- Logos and favicon assets are loaded at runtime based on the `tenantId` and
  are stored through the Game Design Service's
  [asset storage system](./microservices/game-design-service/asset-storage.md).
- The React client loads theme and asset files per tenant at runtime; see
  [Frontend Architecture](./system-architecture-frontend.md).
- Optional layout tweaks can be enabled via **runtime feature flags** defined in
  the Game Design Service and toggled through the
  [Logging & Admin Service](./microservices/logging-admin-service/README.md).
- Locale files are loaded at runtime using `react-i18next` so games can provide
  their own translations.

## World Configuration

- Game worlds are defined entirely through the Game Design Service. Creators can
  add rooms, items and NPCs using the [world editing tools](./microservices/game-design-service/world-editing-tools.md) or by importing JSON files.
- Additional design-time utilities like the [ability & action tools](./microservices/game-design-service/ability-action-tools.md) and [item & equipment balancing](./microservices/game-design-service/item-equipment-balancing.md) help tune gameplay without code changes.
- Published versions are stored per tenant so multiple games can coexist on the
  same infrastructure. Minor fixes can be rolled out as **script-only patch
  versions** linked to a `baseVersionId` so worlds do not need to restart. See
  [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
  for details.

## Scripting Hooks

- Custom scripts drive dynamic events and NPC behaviour using the
  [Automation & Scripting Service](./microservices/automation-scripting-service/README.md).
- The [modding framework](./microservices/game-design-service/modding-framework.md) allows runtime plugins for additional behavior.
- Scripts are versioned alongside other game data and can be hot reloaded by the
  Automation & Scripting Service. Designers may publish a `scriptPatchVersion`
  like `v42-script.3` to update automation without republishing all assets.

## Runtime Settings

- Feature flags and pacing options such as tick intervals are stored in
  per-tenant configuration tables (see
  [Multi-Tenancy](./system-architecture-multi-tenancy.md)).
  Per-tenant tick intervals let games customize pacing across worlds.
- Flags are defined in the Game Design Service but toggled through the
  [Logging & Admin Service](./microservices/logging-admin-service/README.md).
- The [Game Session Service](./microservices/game-session-service/README.md) loads these settings at runtime so changes can take effect without republishing.

These options allow extensive personalization while keeping the underlying platform maintainable.

## 📚 Related Documentation

- [Frontend Architecture](./system-architecture-frontend.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [Game Design Service](./microservices/game-design-service/README.md)
- [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
