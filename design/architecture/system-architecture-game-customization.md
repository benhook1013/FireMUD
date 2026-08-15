# Game Customization

This brief document summarizes optional ways a hosted game can change its look and feel without modifying FireMUD source code. FireMUD runs out of the box with default settings, so none of these customizations are required.

Customization scripts use one DSL and one sandbox, with two artifact/lifecycle roles: embedded game-owned patches follow the Game Design/Game Session pin workflow, while linked plugins remain separately versioned immutable bundles with independent activation metadata. The [DSL lifecycle reference](./system-architecture-scripting-dsl-reference-and-lifecycle.md) owns that distinction; this document retains only the local creator/customization consequence.

## Implementation Status

- Bulk JSON import/export remains deferred. Current creator workflows use service-owned design APIs and world-editing tools.
- Current publication exports ordinary bytes from the first-slice Game Design database source into version-scoped object storage and emits a narrower manifest. Target publication builds and verifies a private candidate before exposing immutable content-addressed objects; all private-candidate/content-addressed bullets and examples below are target state and are not yet the live publication path.

## Theme and Branding

- Designers upload logos, favicons, and theme JSON through the **Game Design Service** at design time. Assets are packaged when a version is published.
- **Target publication:** Game Design builds and verifies a private candidate, then publishes immutable content-addressed objects. Each `manifest.json` entry binds a stable usage role to an `immutableObjectKey`, mandatory actual-byte `contentDigest`, `contentType`, and artifact schema; a delivery URL may be included but is not release authority.
- **Target delivery:** Runtime clients fetch this manifest using its delivery URL or location recorded in the published version metadata and load assets directly from the CDN or through the gateway's `/assets/**` route when a local MinIO instance is used. The Game Design Service is never queried during gameplay, and URL availability or object paths do not replace the attested release metadata.
- A playtest fork uses the branding/assets for the published bundle it is actually launched against. If a fork targets a new `versionId`, it loads that target version's manifest; if it reproduces the source realm's current build, it uses the source build's published manifest. Forks do not create a third independent asset-selection mode.
- The UUID-shaped `versionId` values in the examples in this document are explicitly target-state identifiers. Current Game Design transport examples must use numeric `int64` `versionId` values until the related contracts are migrated together.
- Example: if production is running the published bundle whose canonical `versionId` is `22222222-2222-4222-8222-222222222222` and a playtest fork is launched on the published bundle whose canonical `versionId` is `33333333-3333-4333-8333-333333333333`, testers in the fork see the second bundle's branding while public players in production continue to see the first bundle's branding. Human labels such as `v42` and `v43` are not `versionId` aliases.
- A `manifest.json` is generated for every published version, even when no assets are supplied, so version metadata remains consistent.
- Missing or unavailable assets may use a versioned platform branding default only for explicitly optional presentation roles. Required runtime assets fail publication or launch closed; an empty or malformed manifest is not a blanket fallback authorization.
- **Target manifest binding:** The manifest binds stable usage roles to immutable content-addressed locations and actual-byte digests. URLs, object names, and byte lengths alone do not attest the bytes delivered to the client.
- The manifest can be extended with optional assets such as tutorial images, UI overlays, or CSS snippets.
- **Target realm admission:** `PLAY` success, reconnect resume, and any realm switch return the resolved bundle identity for the selected realm (`versionId`, optional `scriptPatchVersion`, owner-generated opaque `publishedReleaseBundleRef`, and attested `manifestHash` plus its delivery location) so first-party clients can swap theme assets deterministically when production and playtest realms run different builds. The `publishedReleaseBundleRef` is propagated unchanged from the resolved tuple through each of these resolution paths; consumers preserve and compare it byte-for-byte and must not parse, derive, reconstruct, or replace it from tenant, version, bundle, or alias identifiers. Clients verify the fetched manifest against `manifestHash`, then hash every downloaded asset's actual bytes and compare them with that manifest entry's `contentDigest` before applying the asset. Missing, unsupported, or mismatched asset digests fail closed for required roles; optional-role fallback remains allowed only under the explicit optional policy above.

Target-state content-addressed realm-swap example:

- Production realm admission resolves `{ versionId: "22222222-2222-4222-8222-222222222222", scriptPatchVersion: "v42-script.1", publishedReleaseBundleRef: "opaque-release-ref-7f3c9a2b...", manifestHash: "sha256:prod-manifest...", manifestUrl: "https://cdn.example.com/manifests/sha256/prod-manifest..." }`, so the client verifies the content-addressed manifest hash and each downloaded asset's `contentDigest` before applying the first bundle's assets.
- A tester then switches to `playtest-docks`, and `PLAY` resolves `{ versionId: "33333333-3333-4333-8333-333333333333", scriptPatchVersion: "v43-script.2", publishedReleaseBundleRef: "opaque-release-ref-a19d4e6c...", manifestHash: "sha256:playtest-manifest...", manifestUrl: "https://cdn.example.com/manifests/sha256/playtest-manifest..." }`; a manifest or required-asset digest mismatch fails closed before applying the second bundle.
- The client must treat the changed canonical `versionId` as a hard theme boundary: load the second bundle's manifest, swap logos/theme overrides, and render the fork with that bundle's look without mutating the production realm's active theme state.
- If the player returns to production, the next `PLAY` or reconnect resume re-resolves `versionId: "22222222-2222-4222-8222-222222222222"` and the client switches back to the first bundle's manifest.

Target-state content-addressed `manifest.json` example for the production `versionId` above:

```json
{
  "schemaVersion": 1,
  "assets": {
    "branding.logo": {
      "usageKey": "branding.logo",
      "immutableObjectKey": "artifacts/sha256/ab/ab12...",
      "contentDigest": "sha256:ab12...",
      "contentType": "image/png",
      "artifactSchemaVersion": 1,
      "url": "https://cdn.example.com/artifacts/sha256/ab/ab12..."
    },
    "branding.favicon": {
      "usageKey": "branding.favicon",
      "immutableObjectKey": "artifacts/sha256/cd/cd34...",
      "contentDigest": "sha256:cd34...",
      "contentType": "image/x-icon",
      "artifactSchemaVersion": 1,
      "url": "https://cdn.example.com/artifacts/sha256/cd/cd34..."
    },
    "branding.theme": {
      "usageKey": "branding.theme",
      "immutableObjectKey": "artifacts/sha256/ef/ef56...",
      "contentDigest": "sha256:ef56...",
      "contentType": "application/json",
      "artifactSchemaVersion": 1,
      "url": "https://cdn.example.com/artifacts/sha256/ef/ef56..."
    }
  }
}
```

- **Self-hosted S3**: For local development or private deployments, run an S3-compatible service such as MinIO; Docker Compose provides a `minio` service preconfigured with the `firemud-assets` bucket. `ASSET_STORE_ENDPOINT` is the private authenticated S3 API used by Game Design, while target-only `ASSET_STORE_PUBLIC_BASE_URL` identifies the gateway/CDN origin used in public manifest links. The public-base setting and split delivery path are not implemented in the current single-endpoint first slice; private candidate namespaces must not be exposed to clients.

- The React client loads theme and asset files for the currently admitted realm bundle at runtime; see [Frontend Architecture](./system-architecture-frontend.md) for details.

---

## World Configuration

- A default world is available, but creators can define custom worlds entirely through the **Game Design Service**. They add rooms, items, and NPCs through the [world editing tools](./microservices/game-design-service/world-editing-tools.md) and canonical service-owned design APIs. Any package transport must validate and apply through the same versioned authoring contracts rather than becoming a filesystem or second data authority.
- Additional design-time utilities like the [ability & action tools](./microservices/game-design-service/ability-action-tools.md) and [item & equipment balancing](./microservices/game-design-service/item-equipment-balancing.md) help tune gameplay without code changes.
- When multiple versions are published, they are stored per tenant so multiple games can coexist on the same infrastructure. Target-state **script-only patch versions** may link to a `baseVersionId` without republishing unaffected non-script assets, but every patch or plugin change creates a new complete immutable runtime tuple and requires explicit READY, compatibility, and pin rollout. It must not hot-reload a running descriptor or follow a latest patch/plugin alias. See [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md) for details.

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
- Scripts are versioned alongside other game data. Designers may publish a `scriptPatchVersion` like `v42-script.3` to update automation without republishing all assets, but changing a patch or plugin member creates a new recorded immutable runtime tuple and requires explicit READY, compatibility, and pin rollout. Hot reload must not mutate a running descriptor or follow a latest patch/plugin alias.

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
