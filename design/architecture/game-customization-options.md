# 🎮 Game Customization Options

This brief document summarizes optional ways a hosted game can change its look
and feel without modifying FireMUD source code. FireMUD runs out of the box with
default settings, so none of these customizations are required.

## Theme and Branding

- Designers upload logos, favicons, and theme JSON through the Game Design
  Service at design time. Assets are packaged when a version is published.
- At publish time, assets are pushed to an object store (e.g., S3, MinIO, or a
  CDN) under a `tenantId`/`version` path. A `manifest.json` mapping asset keys to
  public URLs is stored alongside them.
- Runtime clients fetch this manifest using the URL recorded in the published
  version metadata and load assets directly from the CDN. The Game Design Service
  is never queried during gameplay.
- A `manifest.json` is generated for every published version, even when no
  assets are supplied, so version metadata remains consistent.
- If the manifest is empty or missing fields, the default platform branding is
  applied.
- The manifest can be extended with optional assets such as tutorial images, UI
  overlays, or CSS snippets.

Example `manifest.json`:

```json
{
  "logo": "https://cdn.example.com/tenant123/v1/logo.png",
  "favicon": "https://cdn.example.com/tenant123/v1/favicon.ico",
  "theme": "https://cdn.example.com/tenant123/v1/theme.json"
}
```

Optional layout tweaks can be enabled via
[runtime feature flags](./microservices/game-design-service/feature-flags.md)
defined in the Game Design Service and toggled through the
  [Logging & Admin Service](./microservices/logging-admin-service/README.md). (TODO: Not yet implemented)

- Tenants can provide locale files that are loaded at runtime using
  `react-i18next`; otherwise, the default language is used. (TODO: Not yet
  implemented)

## World Configuration

- A default world is available, but creators can define custom worlds entirely
  through the Game Design Service. They may add rooms, items and NPCs using the
  [world editing tools](./microservices/game-design-service/world-editing-tools.md)
  or by importing JSON files. (TODO: Not yet implemented)
- Additional design-time utilities like the
  [ability & action tools](./microservices/game-design-service/ability-action-tools.md)
  and [item & equipment balancing](./microservices/game-design-service/item-equipment-balancing.md)
  help tune gameplay without code changes. (TODO: Not yet implemented)
- When multiple versions are published, they are stored per tenant so multiple
  games can coexist on the same infrastructure. Minor fixes can be rolled out as
  **script-only patch versions** linked to a `baseVersionId` so worlds do not
  need to restart. See
  [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
  for details.

## Scripting Hooks

- Custom scripts can drive dynamic events and NPC behaviour using the
  [Automation & Scripting Service](./microservices/automation-scripting-service/README.md).
- The planned [modding framework](./microservices/game-design-service/modding-framework.md)
  will allow runtime plugins for additional behavior. (TODO: Not yet implemented)
- If scripts are used, they are versioned alongside other game data and can be
  hot reloaded by the Automation & Scripting Service. Designers may publish a
  `scriptPatchVersion` like `v42-script.3` to update automation without
  republishing all assets.

## Runtime Settings

- When needed, feature flags and pacing options such as tick intervals can be
  stored in per-tenant configuration tables (see
  [Multi-Tenancy](./system-architecture-multi-tenancy.md)). Per-tenant tick
  intervals are planned. (TODO: Not yet implemented)
- Flags are defined in the Game Design Service (see
  [Feature Flags](./microservices/game-design-service/feature-flags.md)) but
  toggled through the [Logging & Admin Service](./microservices/logging-admin-service/README.md).
  (TODO: Not yet implemented)
- The [Game Session Service](./microservices/game-session-service/README.md)
  loads these settings at runtime when customization is provided so changes can
  take effect without republishing. (TODO: Not yet implemented)

These options allow extensive personalization while keeping the underlying platform maintainable.

## 📚 Related Documentation

- [Frontend Architecture](./system-architecture-frontend.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [Game Design Service](./microservices/game-design-service/README.md)
- [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
