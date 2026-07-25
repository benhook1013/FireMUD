# Game Customization

This brief document summarizes optional ways a hosted game can change its look and feel without modifying FireMUD source code. FireMUD runs out of the box with default settings, so none of these customizations are required.

## Implementation Status

Bulk JSON import/export remains deferred. Current creator workflows use service-owned design APIs and world-editing tools.

## Theme and Branding

- Designers upload logos, favicons, and theme JSON through the **Game Design Service** at design time. Assets are packaged when a version is published.
- At publish time, assets are pushed to an object store (e.g., S3, MinIO, or a CDN) under a `tenantId`/`version` path. A `manifest.json` mapping asset keys to public URLs is stored alongside them.
- Runtime clients fetch this manifest using the URL recorded in the published version metadata and load assets directly from the CDN or through the gateway's `/assets/**` route when a local MinIO instance is used. The Game Design Service is never queried during gameplay.
- A playtest fork uses the branding/assets for the published bundle it is actually launched against. If a fork targets a new `versionId`, it loads that target version's manifest; if it reproduces the source realm's current build, it uses the source build's published manifest. Forks do not create a third independent asset-selection mode.
- Example: if production is running `v42` with the current live logo and a playtest fork is launched on `v43` with a new theme manifest, testers in the fork see the `v43` branding while public players in production continue to see the `v42` branding.
- A `manifest.json` is generated for every published version, even when no assets are supplied, so version metadata remains consistent.
- If the manifest is empty or missing fields, the default platform branding is applied.
- The manifest can be extended with optional assets such as tutorial images, UI overlays, or CSS snippets.
- Realm admission is the runtime resolution point for branding. `PLAY` success, reconnect resume, and any realm switch must return the resolved bundle identity for the selected realm (`versionId`, optional `scriptPatchVersion`, and manifest location/hash or equivalent) so first-party clients can swap theme assets deterministically when production and playtest realms run different builds.

Concrete realm-swap example:

- Production realm admission resolves `{ versionId: "22222222-2222-4222-8222-222222222222", scriptPatchVersion: "v42-script.1", manifestUrl: ".../tenant123/22222222-2222-4222-8222-222222222222/manifest.json" }`, so the client keeps the live `v42` theme.
- A tester then switches to `playtest-docks`, and `PLAY` resolves `{ versionId: "33333333-3333-4333-8333-333333333333", scriptPatchVersion: "v43-script.2", manifestUrl: ".../tenant123/33333333-3333-4333-8333-333333333333/manifest.json" }`.
- The client must treat the changed bundle identity as a hard theme boundary: load the `v43` manifest, swap logos/theme overrides, and render the fork with the `v43` look without mutating the production realm's active theme state.
- If the player returns to production, the next `PLAY` or reconnect resume re-resolves the production bundle and the client switches back to `v42`.

Example `manifest.json`:

```json
{
  "logo": "https://cdn.example.com/tenant123/v1/logo.png",
  "favicon": "https://cdn.example.com/tenant123/v1/favicon.ico",
  "theme": "https://cdn.example.com/tenant123/v1/theme.json"
}
```

- **Self-hosted S3**: For local development or private deployments, run an S3-compatible service such as MinIO; Docker Compose provides a `minio` service preconfigured with the `firemud-assets` bucket, while the Kubernetes manifests under `k8s/minio/` proxy `/assets/**` through the gateway instead of exposing MinIO directly. Set `ASSET_STORE_ENDPOINT` to `https://<gateway-domain>/assets` and supply the bucket and credentials via `ASSET_STORE_BUCKET`, `ASSET_STORE_ACCESS_KEY`, `ASSET_STORE_SECRET_KEY`, and `ASSET_STORE_REGION`.

- The React client loads theme and asset files for the currently admitted realm bundle at runtime; see [Frontend Architecture](./system-architecture-frontend.md) for details.

---

## World Configuration

- A default world is available, but creators can define custom worlds entirely through the **Game Design Service**. They add rooms, items, and NPCs through the [world editing tools](./microservices/game-design-service/world-editing-tools.md) and canonical service-owned design APIs. Any package transport must validate and apply through the same versioned authoring contracts rather than becoming a filesystem or second data authority.
- Additional design-time utilities like the [ability & action tools](./microservices/game-design-service/ability-action-tools.md) and [item & equipment balancing](./microservices/game-design-service/item-equipment-balancing.md) help tune gameplay without code changes.
- When multiple versions are published, they are stored per tenant so multiple games can coexist on the same infrastructure. Minor fixes can be rolled out as **script-only patch versions** linked to a `baseVersionId` so worlds do not need to restart. See [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md) for details.

---

## Command And Effect Declarations

The [Player Command Model](./system-architecture-player-command-model.md) is authoritative for command semantics and the complete command-definition contract. Player commands and gameplay effects are version-scoped Game Design data. Creators use typed Game Design DML/revision operations with the `COMMAND_DEFINITION` revision kind to declare one stable logical command identity, aliases, stage, category, semantic tags, capability requirements, help metadata, execution discipline, and typed execution effects. This is a summary, not an exhaustive schema: the complete definition also carries canonical identity and semantic ownership plus any effect ordering and atomicity rules. Seeded platform commands use the same declaration model as tenant/game-authored commands.

An execution effect declaration identifies a registered safe effect kind, schema version, typed payload, targeting/authorization requirements, and replay/idempotency policy. For example, a blocking action declares a typed action-state effect; it is not inferred merely because the command carries a broad `COMBAT` tag. Categories and tags remain descriptive policy metadata for activity, presentation, analytics, and subscriptions.

Game Design validates these declarations during revision and publish. Publication fails closed for unknown effect kinds, unsupported schema versions, invalid payloads, unsafe effect composition, duplicate aliases, or command/effect bindings that collide with reserved platform behavior. Publication freezes an immutable command-definition artifact and digest for the version. Runtime command resolution uses only the artifact matching the admitted version and approved script-patch identity; it must not silently fall back to process-local action configuration or reinterpret a command against newer draft data.

The platform deliberately does not execute arbitrary SQL/DML text, Java snippets, or unvalidated script payloads as command behavior. The generic runtime code owns safe effect schemas, validation, authorization, durable ordering, replay, and execution; DML selects and configures approved effects within those schemas. See [Player Command Model](./system-architecture-player-command-model.md#typed-execution-effects) for the canonical command/effect boundary.

---

## Scripting Hooks

- Custom scripts can drive dynamic events and NPC behaviour using the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md).
- The [modding framework](./microservices/game-design-service/modding-framework.md) allows runtime plugins for additional behavior.
- Scripts are versioned alongside other game data and can be hot reloaded by the Automation & Scripting Service. Designers may publish a `scriptPatchVersion` like `v42-script.3` to update automation without republishing all assets.

---

## Runtime Settings

- Feature flags and pacing options such as tick intervals are stored in per-tenant configuration tables (see [Multi-Tenancy](./system-architecture-multi-tenancy.md)). Per-tenant tick intervals let games customize pacing across worlds.
- Flags are defined in the Game Design Service (see [Feature Flags](./microservices/game-design-service/feature-flags.md)) but toggled through the [Logging & Admin Service](./microservices/logging-admin-service/README.md).
- The [Game Session Service](./microservices/game-session-service/README.md) loads these settings at runtime so changes can take effect without republishing.

These options allow extensive personalization while keeping the underlying platform maintainable.

## Related Documentation

- [Automation & Scripting Service](./microservices/automation-scripting-service/README.md)
- [Frontend Architecture](./system-architecture-frontend.md)
- [Game Creator Guide](../user-guides/game-creator-guide.md)
- [Game Design Service](./microservices/game-design-service/README.md)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md)
