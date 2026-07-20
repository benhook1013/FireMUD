# In-Game Modding and Plugin Framework

This document outlines the linked-plugin system that lets administrators extend a published game without republishing a full version.

Plugins use the same **component-based DSL**, compiler, validator, sandbox, and execution runtime as ordinary game-owned scripts so custom logic can be hot reloaded safely. A plugin is a packaging and lifecycle role, not a separate execution language or automatic trust tier. [ADR 0108](../../decisions/adr-0108-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md) defines this boundary.
Operator-facing plugin lifecycle UX is surfaced through Logging & Admin, but the authoritative runtime mutation APIs (`GetPluginStatus`, `SetPluginActiveVersion`, `DisablePlugin`, `DrainPlugin`) are owned by Automation & Scripting as described in the scripting control-plane docs.

Plugin bundles are admitted through the Game Design Service and stored in the same asset repository used for other design files. Marketplace catalogs, repositories, and file upload are provenance and distribution channels for the same bundle contract; they do not select different runtime semantics.

The current implementation is partial. It implements allowlisted Ed25519-signed ZIP intake, immutable publication metadata and asset storage, and instance-scoped activation seams. It does not yet implement every provenance channel, unsigned platform-attested intake, the complete manifest contract, or one complete package-to-compiled-runtime path.

## Goals

- Enable runtime loading of approved plugins written in the same **component-based** scripting DSL used for embedded game automation.
- Provide a secure sandbox so plugins cannot access unauthorized data or system resources.
- Allow plugins to hook into game events exposed by the Game Logic and World Management services.
- Isolate plugin enablement and data per `tenantId` and `gameInstanceId` so a tenant can run multiple instances with different plugin selections safely.
- Forward plugin execution metrics and error logs to the Logging & Admin Service for auditing.

## Trust Model & Roles

Plugins run with the same core execution model as other scripts but retain separate package provenance, approval, capability, update, and activation governance:

- Only appropriately privileged principals (for example, tenant administrators or platform operators, depending on environment configuration) may upload or enable plugins for a given `tenantId`.
- Every accepted plugin version must have an immutable bundle digest, complete validation evidence, an authorized explicit approval, and a platform acceptance attestation bound to that exact version and digest before it is eligible for activation.
- An author signature is optional publisher-provenance evidence. External unsigned packages may be accepted only when operator policy permits them and the mandatory approval and platform-attestation boundary succeeds; a warning alone never makes a package activatable. Hosted operators may prohibit unsigned packages entirely.
- Different environments may use distinct signer allowlists, acceptance authorities, and role policies so that development acceptance cannot be promoted directly into production without review.
- Package origin, marketplace listing, or author signature does not grant elevated DSL capabilities or authorize automatic updates. Capabilities are granted by platform/operator policy to an exact package version and target scope.

The Logging & Admin Service exposes management APIs for listing, enabling, disabling, and inspecting plugins; these APIs enforce the same role model and provide audit trails for all plugin state changes.

## Script and Plugin Roles

FireMUD has one DSL with two release roles:

- A **script** is one executable graph or handler entrypoint. Ordinary embedded scripts are game-owned, editable through the Game Design revision model, and released in an immutable game version or script-only patch.
- A **linked plugin** is an immutable, independently versioned and independently activated bundle containing DSL graphs, bindings, bounded configuration, and optional plugin-owned assets. It retains its own provenance, compatibility, enable, drain, disable, update, and rollback history.
- A marketplace, repository, local file, or future in-product package builder may produce the same linked-plugin artifact. Origin affects approval, publisher identity, update discovery, and operator policy, not compilation or execution.

Stable `graphId`, `bindingId`, `pluginId`, and `pluginVersionId` values originate in the immutable accepted bundle regardless of distribution channel. Linked bundles are not edited in place; a content change creates a new `pluginVersionId`.

A package containing ordinary world, entity, ability, action, or other base-version DML is not eligible for independently layered runtime activation. Game Design must materialize that content into a Draft as game-owned data and publish a new game version. Linked plugins may reference compatible published contracts and carry plugin-owned assets, but they do not become a second content authority above the base release.

## Canonical Plugin Bundle Contract (Required)

Plugin bundles are not opaque archives. Authoring, validation, audit, and activation all consume one canonical immutable contract inside the bundle. An optional author signature and the mandatory platform acceptance attestation bind to the same exact bundle digest.

Required payload contents and optional provenance envelope:

- `plugin-manifest.json` is the authoritative manifest for the bundle. Game Design and Automation & Scripting must derive activation metadata from this file and its platform acceptance attestation rather than from mutable upload-time form fields or object-store metadata.
- `signatures.json` contains the optional author-signature envelope described below when publisher signatures are supplied or required by operator policy.
- One or more DSL graph definition files referenced by `plugin-manifest.json`.
- Zero or more static assets referenced by `plugin-manifest.json`.

`plugin-manifest.json` must define at least:

- `schemaVersion` – required manifest schema version; unsupported schema versions fail closed at upload/publish/activation.
- `pluginId` – stable logical plugin identity reused across versions.
- `pluginVersionId` – immutable version identity for this bundle only.
- `baseVersionId` – the exact published game version this plugin version targets.
- `abilitySchemaDigest` – the immutable ability schema digest for that same `baseVersionId`.
- `displayName` and optional human-facing description/version notes.
- `entrypoints[]` – the DSL graphs included in the bundle, with stable graph identifiers and file references.
- `bindings[]` – the event subscriptions and target scopes declared by the bundle, using the canonical binding model defined below.
- `requiredComponents[]` – DSL component identifiers the bundle requires so policy validation can be deterministic.
- `requiredCapabilities[]` – capabilities requested by the plugin. This declaration never grants a capability; an exact-version, target-scope platform/operator grant is required separately.
- `assetRefs[]` – optional plugin-local asset references.

Attested `assetRefs[]` entries must define at minimum:

- `assetId` – stable identifier unique within the plugin version.
- `path` – canonical bundle-relative path.
- `contentHash` – immutable digest of the referenced asset bytes.
- `contentType` – media type.
- optional bounded metadata such as `sizeBytes`, localization tag, or usage hint.

Contract rules:

- `pluginVersionId` is immutable content identity. Republishing after any manifest, graph, binding, or asset change requires a new `pluginVersionId`.
- Provenance or approval evidence may change without a new `pluginVersionId` only when the attested bundle digest and all payload bytes are identical. The same immutable plugin version may therefore carry additional author signatures or environment approvals over time, but any manifest, graph, binding, configuration, or asset-byte change still requires a new `pluginVersionId`.
- `baseVersionId` compatibility is exact, not fuzzy. A plugin version targets one published game version only.
- `abilitySchemaDigest` must match the immutable digest attested for that `baseVersionId`; it is recorded in Game Design metadata and re-checked at activation time.
- A claimed starter-profile identity is a discovery hint only. Compatibility is decided from the target release's actual stable identifiers, schemas, extension contracts, granted capabilities, and digests because profile-supplied content may have been edited or removed after materialization.
- Game Design must persist indexed metadata from the manifest (`pluginId`, `pluginVersionId`, `baseVersionId`, `abilitySchemaDigest`, provenance channel, optional signer identity, acceptance-attestation identity, requested and granted capabilities, and validation status) so UIs and control-plane APIs do not need to unpack bundles for routine reads.
- When `assetRefs[]` is non-empty, Game Design must also persist `distributionManifestHash` and `distributionManifestPath` for the plugin-version distribution manifest it writes during `PublishPluginVersion`.
- Automation & Scripting must treat the immutable manifest plus its platform acceptance attestation as the source of truth for runtime activation metadata. It may cache extracted fields, but it must not trust mutable side-channel metadata over the attested artifact.
- If `assetRefs[]` are runtime-consumable, runtime discovery must flow through the attested manifest metadata that Game Design publishes and, where instance/runtime consumers need object-store access, through the attested release/distribution metadata derived from that manifest rather than undocumented bucket key conventions.

### Plugin Asset Distribution

Plugin assets are distributed through a plugin-version-scoped manifest, not through the base game version's `published_release_bundle`. A plugin version targets one already published `baseVersionId`, but publishing the plugin must not mutate the immutable release attestation for that base version.

When `assetRefs[]` is empty, no plugin distribution manifest is required. When `assetRefs[]` is non-empty, `PublishPluginVersion` must write a `plugin-distribution-manifest.json` under a Game Design-owned object-store prefix scoped to `(tenantId, pluginId, pluginVersionId)`, persist its hash in indexed plugin metadata, and expose it through `GetPublishedPluginVersion` / `ListPluginVersionStatuses`. Runtime consumers resolve plugin assets only through that metadata and the attested `assetRefs[]`; they must not construct object-store paths from tenant or plugin identifiers.

The distribution manifest must include:

- `tenantId`, `pluginId`, `pluginVersionId`, `baseVersionId`, and `abilitySchemaDigest`.
- `manifestHash` and `manifestSchemaVersion`.
- `bundleDigest`, platform acceptance-attestation identity, provenance channel, and optional `signerKeyId` from verified publisher evidence.
- `assets[]` entries keyed by attested `assetId`, with canonical object-store URL or opaque storage key, content hash, media type, byte size, and optional localization or usage metadata.

`PublishPluginVersion` must fail before `PUBLISHED` if any attested `assetRefs[]` entry is missing from the bundle, cannot be exported, has a digest mismatch, or cannot be represented in the distribution manifest. Exact-byte repair rules mirror version asset repair: a published plugin distribution manifest is immutable, and repair may only reproduce bytes that match the persisted manifest hash.

## Provenance, Acceptance Attestation, and Key Lifecycle (Required)

Every plugin uses the mandatory immutable-digest and platform-acceptance boundary. Author signing is an optional stronger provenance channel unless the target environment requires it. When author signatures are present, their key lifecycle must be specified precisely enough that operators can rotate keys and revoke signers without ambiguity.

Minimum requirements:

- **Bundle digest**: each uploaded bundle computes a stable `bundleDigest` (for example SHA-256 over the canonical bundle bytes) which is the input to author-signature verification when present and is always recorded in approval, acceptance-attestation, and audit evidence.
  - **Canonicalization (required)**: the bundle format must define “canonical bundle bytes” precisely so the same logical bundle always hashes the same:
    - Archive format is fixed (for example `tar` with deterministic headers, or `zip` with normalized timestamps).
    - File ordering is deterministic.
    - Timestamps/UID/GID/permissions are normalized or excluded from the digest input.
    - The bundle digest input must exclude any transport-layer wrapper (for example HTTP multipart boundaries).
    - The bundle digest input must also exclude `signatures.json` itself. `bundleDigest` is computed over the canonical payload set named by `plugin-manifest.json`, referenced graph files, and any referenced assets so the signature envelope does not recursively hash itself.
- **Bundle ingestion safety limits (required)**:
  - Upload and extraction must enforce bounded limits before runtime validation (for example `maxBundleBytes`, `maxExpandedBytes`, `maxFileCount`, and `maxCompressionRatio`).
  - Extraction and manifest parsing must use bounded timeouts and memory limits; over-limit bundles must fail closed.
  - Limits and failures must be audit-visible with deterministic bounded reason codes (for example `bundle_too_large`, `bundle_compression_ratio_exceeded`, `bundle_file_count_exceeded`, `bundle_parse_timeout`).
- **Platform acceptance (required)**:
  - Game Design records the authorized approver, approval scope, provenance channel, exact `pluginId`, `pluginVersionId`, and `bundleDigest`, validation evidence, requested and granted capabilities, acceptance authority, and acceptance time.
  - The resulting platform acceptance attestation is required for publication and activation even when a valid author signature exists.
  - An unsigned package with only a warning acknowledgment fails closed. Environments may reject unsigned intake before approval.
- **Author-signature algorithm**: author-signed plugin bundles use **Ed25519**.
- **Key identity**: every supplied author signature is tied to a `signerKeyId` (stable identifier for the public key used to verify the signature).
- **Signature envelope (when supplied or required by policy)**:
  - Author-signed bundles contain a machine-readable signature manifest (for example `signatures.json`) that includes `bundleDigest`, `signerKeyId`, the `ed25519Signature` bytes, and an optional `signatureCreatedAt`.
  - Multiple signatures may be present; verification succeeds if at least one signature is by an allowlisted `signerKeyId` and none are by explicitly revoked keys.
- **Verification points**:
  - Game Design verifies every supplied author signature at upload time and records `bundleDigest`, optional `signerKeyId`, and signature-verification outcome.
  - Automation & Scripting verifies the platform acceptance attestation at load/activation time and, for author-signed packages, also enforces current signer policy. It rejects activation if required evidence is absent, mismatched, revoked, stale beyond its bound, or not allowed for the environment.
- **Rotation**:
  - Operators can introduce new signer keys without downtime by adding a new `signerKeyId` to the allowlist for the environment.
  - Old keys may remain valid for existing bundles during a transition window, but new uploads should prefer the newest active key.
- **Revocation**:
  - Operators can revoke a signer by removing its `signerKeyId` from the allowlist and adding it to a revocation list.
  - When a signer is revoked, subsequent loads/activations of bundles signed by that key must fail, and already-enabled plugins must transition to `pluginState=DISABLED` with mandatory `statusReason=signer_revoked`; triggers are rejected and the reason is recorded in `script_event_audit`.
  - Operators can independently revoke an exact package version or platform acceptance attestation, including an unsigned package. Exact-package revocation must not require inventing a signer identity.
- **Propagation (required)**:
  - Platform acceptance, exact-package revocation, capability-grant, and applicable signer policy must be distributed to runtime services through authenticated, versioned configuration or control-plane reads with a bounded refresh interval.
- Automation & Scripting must refresh applicable provenance and acceptance policy on a bounded cadence (for example every 60 seconds) and must disable affected plugins within a fixed operator SLO (for example “revocation disables affected plugins within 5 minutes”). The current Automation runtime implements the author-signed subset through a scheduled plugin-policy reconciliation sweep over enabled plugin runtime states; it disables active plugins when Game Design publication metadata reports signer revocation, blocked/missing component policy, unavailable policy metadata, or a no-longer-published plugin version.
  - Disablement due to revocation must emit an operator-visible control-plane event and be visible in audit tooling so operators can prove when revocation took effect.
  - Runtime signer-policy visibility must be queryable via control-plane read APIs (for example `GetSignerPolicyConvergence`) so operators can verify policy propagation before and after revocation. Before resuming normal admission after a revocation or policy repair, operators should use those read surfaces together with the scripting control-plane convergence/drain reads for the affected scope to confirm that policy propagation, plugin disablement, and any required draining have all converged.
  - If required acceptance, capability, exact-package-revocation, or applicable signer policy cannot be refreshed or verified for a scope beyond max-age, plugin admission fails closed. The current author-signed implementation reports `finalOutcome=signer_policy_unavailable`; generalized provenance-policy outcome naming remains implementation work.
  - Any override that permits plugin admission while required provenance policy is unavailable must be explicit, time-bounded, scoped, and operator-audited, and must auto-expire back to fail-closed mode.

Logging & Admin must surface the provenance channel, platform acceptance status, approver and scope, `bundleDigest`, and optional signer identity and verification status so operators can explain why a plugin version was accepted or rejected.
Logging & Admin must also surface signer-policy propagation status and revocation application events (for example `SignerPolicyVersionObserved` and `SignerRevocationApplied`) so operators can prove enforcement timing across services.

## Outline

1. Plugins use one immutable package contract regardless of whether they arrive through a marketplace, repository, or file upload.
2. The Game Design Service validates archive and manifest contents, computes the immutable digest, verifies any supplied author signatures, records explicit approval, and issues the mandatory platform acceptance attestation before publication. See [Asset Storage Setup](asset-storage.md).
3. The Automation & Scripting Service executes plugin code with strict quotas similar to regular scripts.
4. A registry tracks which plugins are active for each game instance and exposes toggle APIs via the Logging & Admin Service.
5. Plugins can subscribe to events such as `onEnterRoom` or `onItemUse` to inject custom behavior.
6. Execution metrics and error logs are forwarded to the Logging & Admin Service for monitoring.
7. Plugin bundles are versioned as design assets but are independently publishable and operator-activatable against exactly one already published `baseVersionId`; a new game-version publish requires a new plugin version whenever the plugin must target a different `baseVersionId` or `abilitySchemaDigest`.

## Sandbox Capabilities & Quotas

At runtime, plugins use the same **component-based scripting DSL and sandbox** as the Automation & Scripting Service:

- Plugin graphs are authored from the same curated DSL component catalog as embedded scripts. Availability is decided from the exact-version, target-scope capability grant and platform/operator policy, not from marketplace, repository, file, signed, unsigned, or in-product origin.
- A manifest's `requiredComponents[]` and `requiredCapabilities[]` declare requirements only. They cannot grant access, and importing a package into an in-product surface must not launder it into a broader capability set.
- Plugins do not gain direct access to databases, Redis, or process internals; all state changes must still flow through domain services and the Game Session Service.
- Each plugin instance is represented as a script-like runtime object in the Automation & Scripting Service, typically tagged with `scriptType=PLUGIN` and annotated with `pluginId` and `pluginVersionId`. It participates in:
  - Per-script quotas and concurrency limits enforced by `ScriptQuotaService`.
  - Per-tenant budgets, including priority tiers (for example, `high`, `normal`, `background`).
  - Cluster-level automation ceilings and automation tick budgets, as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

Plugin executions appear in `script_event_audit` with the same identifiers as regular scripts, plus plugin-specific metadata, and contribute to the same automation metrics, enabling operators to monitor plugin behavior without a separate observability pipeline.

### Timer & Event Guarantees

Plugins share the same **event and timer semantics** as core scripts:

- All plugin triggers are at-most-once per `scriptEventId`; the scheduler never re-runs the plugin’s DSL graph for the same trigger, even if downstream services retry idempotent operations.
- Timer-based handlers such as `onInterval` and `onTimerExpire` are **best-effort**. Individual firings may be skipped or delayed when per-script quotas, per-tenant budgets, or cluster ceilings are reached, and skipped firings are not backfilled later. Subsequent firings still follow the configured cadence as capacity allows.
- Plugin logic must therefore be designed to tolerate missed or delayed events (for example, by recomputing from current world state rather than assuming every interval has executed) and to keep effects idempotent with respect to Trigger Identity plus the region-scoped tick timeline (for example `scriptEventId` and `(regionEpoch, tickId)`), following the same rules described in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`.

These guarantees ensure that plugins do not rely on stronger delivery semantics than the underlying scripting engine provides and that their behavior remains predictable under load.

### Validation Rules for Plugins

From a validation perspective, plugins follow the same core rules as regular scripts, with additional admission checks driven by their independent package and activation lifecycle:

- The Game Design Service applies the same **graph validation and loop safety analysis** to plugin graphs as it does to regular scripts. Unsafe graphs (for example, unbounded cycles without guard nodes) are rejected and must be fixed before a plugin version can be enabled.
- Platform owners maintain component and capability policy for linked plugins. Components that are not granted for the exact plugin version and target scope are unavailable even if the package declares them or its source is otherwise trusted.
- Deprecation and migration flows apply equally: plugins using deprecated or unsafe components appear in “requires migration” views and are not eligible to run until migrated and republished.

Validation results for plugins surface through the same tooling as core scripts (Game Design and Logging & Admin UIs), but may include plugin-specific reason codes so operators can distinguish “invalid graph” from “disallowed component for plugin use”.

## Design-Time Plugin Version Lifecycle

Game Design owns the authoring and publication lifecycle for plugin versions before any instance-scoped activation occurs.

Each `(tenantId, pluginId, pluginVersionId)` must have one canonical design-time status:

- `DRAFT` – authoring metadata exists but no accepted immutable bundle has been stored.
- `UPLOAD_REJECTED` – bundle ingestion failed before publication, for example due to archive safety limits, malformed manifest, digest failure, invalid supplied signature, or an environment policy that prohibits its provenance channel.
- `ATTESTED` – the bundle passed bounded intake, digest calculation, manifest validation, any required author-signature verification, explicit authorization, and platform acceptance attestation. This is a durable operator-visible state, not merely an internal transient step; a version may remain here indefinitely until publication is requested or abandoned. The current implementation's narrower `SIGNATURE_VERIFIED` status represents only the signed-input subset of this target state.
- `VALIDATION_FAILED_DESIGN` – Game Design completed design-time validation and rejected the version due to deterministic authoring errors such as invalid bindings, disallowed components, `baseVersionId` mismatch, or `abilitySchemaDigest` mismatch.
- `PUBLISHED` – the plugin version is accepted into immutable design-time history and is eligible for runtime activation against matching instances.
- `SUPERSEDED` – a later plugin version for the same `pluginId` exists; older versions remain immutable historical records and are not eligible for runtime activation.
- `REVOKED_DESIGN` – the previously published design artifact remains historically readable, but signer revocation, exact-package or acceptance-attestation revocation, or another design-time trust decision has made the version ineligible for further runtime activation.

Required lifecycle semantics:

- Only `PUBLISHED` plugin versions are eligible inputs to runtime activation APIs such as `SetPluginActiveVersion`.
- Transitioning a plugin version to `SUPERSEDED` removes it from the set of activatable versions. If operators need to return to equivalent plugin logic later, they must publish a new `pluginVersionId` rather than reactivating the superseded historical version.
- `UPLOAD_REJECTED` and `VALIDATION_FAILED_DESIGN` are design-time terminal failures and must not create or mutate runtime registry rows.
- Transition into `PUBLISHED` records the indexed manifest metadata, validation results, provenance and optional signer metadata, capability grants, platform acceptance attestation, and publication timestamp in Game Design.
- Transition into `REVOKED_DESIGN` preserves immutable publication history while making the version ineligible for future activation. Runtime disablement of already active instances is still executed through Automation & Scripting control-plane flows.
- Logging & Admin may inspect all design-time states, but it must not bypass them by attempting to activate a non-`PUBLISHED` plugin version.
- Game Design must expose read surfaces such as `GetPublishedPluginVersion(tenantId, pluginId, pluginVersionId)` and `ListPluginVersionStatuses(tenantId, pluginId?)`, plus a durable `PluginVersionStatusChanged` event family, so authoring UIs and operator tooling read one authoritative publication state model.

Required write path:

- `UploadPluginBundle` accepts the bundle bytes, performs archive safety checks, canonicalization and digest calculation, verifies any supplied or policy-required author signature, extracts the manifest, and persists indexed provenance metadata.
  - After authorized explicit approval, complete validation, and platform acceptance attestation, success moves the version to `ATTESTED`.
  - Deterministic ingestion, attestation, approval-policy, or signature failures move the version to `UPLOAD_REJECTED`.
- `PublishPluginVersion` is the explicit design-time publication step for a previously uploaded bundle version.
  - It runs design-time validation over graphs, bindings, component policy, `baseVersionId`, and `abilitySchemaDigest`.
  - Validation failures move the version to `VALIDATION_FAILED_DESIGN`.
  - Success moves the version to `PUBLISHED` and emits `PluginVersionStatusChanged`.
- Re-invoking either call with the same `(tenantId, pluginId, pluginVersionId)` must be idempotent: once a version is immutable, callers may observe the existing status but must not overwrite attested content in place.

### Plugin Activation Failure Matrix

Runtime/operator-facing activation outcomes must remain deterministic:

| Condition | Canonical outcome |
| --- | --- |
| Plugin version is not `PUBLISHED` (including `SUPERSEDED`) | Reject activation as ineligible historical or unpublished version |
| Platform acceptance attestation or explicit approval is missing, mismatched, stale, or revoked | Reject activation; a warning acknowledgment is not acceptance evidence |
| Signer revoked or no longer trusted | Reject activation; published history remains readable but runtime enablement is blocked |
| Required provenance or signer-policy lookup unavailable | Fail closed for activation until policy can be evaluated |
| `baseVersionId` does not match the instance runtime version | Reject activation |
| `abilitySchemaDigest` does not match the instance-bound digest | Reject activation |
| Actual stable profile/game contracts do not satisfy package requirements | Reject activation; a profile label does not override the mismatch |
| Required component/capability grant is absent or blocks the plugin | Reject activation with deterministic policy error |
| Activation reconciliation/writeback fails after intent is recorded | Leave runtime state unchanged or mark reconciliation failure explicitly; do not pretend activation succeeded |

### Minimal Bundle Example

This example shows the minimum payload shape expected by the publication pipeline. Author-signature and platform-acceptance envelopes bind to the resulting bundle digest outside this payload:

```json
{
  "pluginId": "town-crier",
  "pluginVersionId": "town-crier-v3",
  "baseVersionId": "game-v12",
  "abilitySchemaDigest": "sha256:9dd1b7c2...",
  "displayName": "Town Crier",
  "entrypoints": [
    {
      "graphId": "announce-arrival",
      "path": "graphs/announce-arrival.json"
    }
  ],
  "bindings": [
    {
      "bindingId": "announce-on-enter-market",
      "orderIndex": 100,
      "requiresExclusiveEvent": false,
      "eventType": "onEnterRegion",
      "targetScopeType": "REGION",
      "targetSelector": {
        "regionTemplateId": "regionTemplateId:market-square"
      },
      "entrypointGraphId": "announce-arrival"
    }
  ],
  "requiredComponents": [
    "dialog.say",
    "condition.entity_flag"
  ],
  "requiredCapabilities": [],
  "assetRefs": []
}
```

Expected publication behavior for this example:

- `UploadPluginBundle` verifies the archive, digest, manifest shape, and any supplied author signatures, then persists indexed metadata for `town-crier-v3`; explicit approval and platform acceptance are still required before publication.
- `PublishPluginVersion` validates that `regionTemplateId:market-square` exists in `game-v12`, that `announce-arrival` is present and safe, and that the plugin remains compatible with `sha256:9dd1b7c2...`.
- `SetPluginActiveVersion` may later activate `town-crier-v3` only for instances whose `runtimeVersionId` is `game-v12` and whose bound ability schema digest matches the same value.
- If `assetRefs[]` included an entry such as `"assetRefs": [{"assetId": "bell-sfx", "path": "assets/bell.ogg"}]`, Game Design would export that asset into the plugin-version distribution manifest, persist the manifest hash with the plugin metadata, and expose the runtime-discoverable asset only through that manifest. Runtime consumers would resolve `bell-sfx` through the published plugin metadata, not by constructing object-store paths directly.

### End-to-End Publication Sequence

One canonical happy path plus failure path:

1. A creator uploads `town-crier-v3` with `UploadPluginBundle`.
2. Game Design enforces archive safety limits, computes the digest, verifies any supplied author signatures, extracts and validates `plugin-manifest.json`, records explicit approval and platform acceptance, persists indexed metadata, and sets status to `ATTESTED`.
3. A creator or operator invokes `PublishPluginVersion` for the same `(tenantId, pluginId, pluginVersionId)`.
4. Game Design validates graphs, bindings, component policy, `baseVersionId`, and `abilitySchemaDigest`.
5. If validation succeeds, Game Design sets status to `PUBLISHED` and emits `PluginVersionStatusChanged`.
6. Logging & Admin may then invoke `SetPluginActiveVersion` for a matching game instance; Automation & Scripting admits the plugin only if the instance `runtimeVersionId` and ability digest match the published metadata.

Failure example:

1. `UploadPluginBundle` succeeds and leaves the version in `ATTESTED`.
2. `PublishPluginVersion` discovers that binding target `regionTemplateId:market-square` does not exist in the plugin's exact `baseVersionId`.
3. Game Design sets status to `VALIDATION_FAILED_DESIGN`, emits `PluginVersionStatusChanged`, and does not create or mutate runtime registry state.
4. Any later `SetPluginActiveVersion` attempt for that `pluginVersionId` must fail deterministically because the version is not `PUBLISHED`.

## Plugin Lifecycle & Rollback

Plugins follow a lifecycle similar to script patches but scoped to `<tenantId, gameInstanceId, pluginId>` so a tenant can run multiple game instances with different plugin selections safely.

- Plugins do **not** participate in the script-patch `onLoad` lifecycle. There is no plugin-scoped `onLoad` or `onUnload` contract in the first implementation slice.
- Plugin activation therefore consists only of:
  - platform acceptance-attestation, optional author-signature, capability-grant, and policy verification,
  - graph validation and compatibility checks,
  - loading the new plugin version into the instance-scoped runtime registry, and
  - reconciling any plugin-owned derived scheduler state such as timers.
- Any plugin setup that would otherwise require startup code must be expressed through normal event handlers (`onSpawn`, `onEnterRegion`, `onInterval`, custom events) or explicit operator/admin workflows. Implementations must not invent an implicit plugin initialization hook.

Design-time publication and runtime activation are separate:

- Publication in Game Design means the plugin bundle is immutable, platform-attested, validated, explicitly approved, and available for activation. Author-signature provenance may additionally apply.
- Activation in Automation & Scripting means a `PUBLISHED` plugin version has been selected for one `(tenantId, gameInstanceId, pluginId)` and admitted into the runtime registry.
- A plugin version that is `PUBLISHED` in Game Design may still be `DISABLED` or never activated for any instance.
- Game Design publication visibility and Automation runtime state must remain separate read surfaces. Operator tooling should read immutable publication metadata (for example `GetPublishedPluginVersion`) alongside runtime activation state (`GetPluginStatus`) rather than relying on one synthetic plugin-state enum to encode both concerns.

- Each plugin version is identified by `pluginVersionId`. A registry in the Automation & Scripting Service tracks, per `<tenantId, gameInstanceId, pluginId>`:
  - `activeVersionId` – the plugin version currently enabled.
  - `pendingVersionId` – a plugin version being loaded or validated.
  - `pluginState` – canonical runtime state: `ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, or `FAILED`.
- Enabling a plugin sets `pluginState=ENABLED` for a `<tenantId, gameInstanceId, pluginId, pluginVersionId>` and allows the scheduler to admit triggers for that plugin, subject to quotas and budgets.
- Disabling a plugin can follow two modes:
  - **Hard disable** – immediately marks the plugin `DISABLED`; new triggers are rejected at admission (`finalStage=ADMISSION`, `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`, while in-flight runs complete under existing budgets.
  - **Disable after drain** – transitions the plugin to `DRAINING` until queued triggers are processed or expired, then marks it `DISABLED`. New triggers are rejected once draining begins.
- Updating a plugin involves setting a new `pendingVersionId`, loading and validating the new plugin graphs and bindings, reconciling plugin-owned timers and other derived scheduler state, and then atomically switching `activeVersionId` if validation succeeds. If validation or activation-state reconciliation fails, the new version is marked `FAILED`, `activeVersionId` remains unchanged, and triggers for the failed version are rejected at admission with an appropriate `finalOutcome` (for example, `version_unavailable` or `plugin_version_failed`).

Plugin triggers share the same Trigger Identity and `scriptEventId` lifecycle as regular scripts. Each invocation is recorded in `script_event_audit` with the required Trigger Identity fields (including `tenantId`, `gameInstanceId`, and for gameplay/runtime triggers `regionEpoch`) plus `pluginId` / `pluginVersionId` and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`) so operators can correlate plugin behavior with publish and enable/disable operations and still distinguish “DSL evaluated” from “accepted into tick queues”.

Certain safety decisions are **platform-wide and not overridable by tenant administrators**:

- Plugin component allowlists and any global “blocked component” flags are controlled by platform operators.
- Plugin-level quotas and budgets may be stricter than for core scripts by default; tenant administrators can lower their own plugin activity (for example, by increasing intervals or disabling plugins) but cannot raise plugin limits beyond operator-defined ceilings.

This ensures that even trusted tenant administrators cannot inadvertently weaken the global safety posture for plugins.

### Canonical Binding Model

Plugin bindings are authored as immutable attested design-time data, not as ad hoc instance-local toggles.

- The authoritative `bindings[]` array lives in `plugin-manifest.json` and is part of the digest-bound, platform-attested bundle.
- Each binding must declare:
  - `bindingId` – stable identity within the plugin version.
  - `orderIndex` – integer ordering key used by the shared script/plugin handler ordering contract.
  - `requiresExclusiveEvent` – boolean exclusivity request; defaults to `false` if omitted, and `true` is valid only when operator policy explicitly allows plugin exclusivity.
  - `eventType` – the subscribed event or timer kind.
  - `targetScopeType` – one of `GLOBAL`, `REGION`, `ENTITY_TEMPLATE`, `COMMAND_ALIAS`, or another explicitly documented scope added later.
  - `targetSelector` – the typed selector object for that scope, using the selector contracts below.
  - `entrypointGraphId` – which declared graph handles the binding.
  - Any bounded binding parameters needed for validation, such as interval cadence.
- Binding targets must reference published base-version assets that exist under the plugin version’s exact `baseVersionId`.
- Rebinding is a content change. Adding, removing, or retargeting any binding requires publishing a new `pluginVersionId`; instance activation only chooses among published plugin versions and does not edit bindings in place.
- Instance-scoped enablement remains separate from binding definition: Logging & Admin chooses whether a published plugin version is active for a given game instance, but it does not rewrite the attested binding set during activation.

Handler identity and ordering:

- The schedulable handler identity for a plugin binding is `(tenantId, gameInstanceId, pluginId, pluginVersionId, bindingId)`. `pluginId` alone is never sufficient because one plugin version can declare multiple bindings for the same event.
- Trigger Identity, dedupe, audit, quota attribution, timer ownership, and drain/disable cleanup must retain `bindingId` alongside `pluginId` and `pluginVersionId` whenever the unit of work is binding-scoped.
- Runtime ordering follows the shared scripting rule: `orderIndex ASC`, `handlerType ASC`, then handler identity ASC. For plugin handlers, the final tie-breaker is `(pluginId, bindingId)`.
- For non-exclusive fan-out, Automation assigns a durable `handlerSequence` from that order and preserves it through handler work, command handoff, and final application. Plugin execution priority and worker timing do not override semantic handler order.
- At most one binding in the complete resolved handler set for `{tenantId, gameInstanceId, target identity, eventType, eventSchemaVersion}` may have `requiresExclusiveEvent=true`. An authorized exclusive binding is selected before fan-out and becomes the sole handler; no core-script or plugin sibling runs before or after it, and failure does not cause sibling fallback.
- Game Design rejects publish-time-known multiple-exclusive or unauthorized plugin claims against the exact `baseVersionId`. Automation & Scripting re-checks the complete resolved base-script plus active-plugin set during instance activation because active selections and bindings from different declared scopes may converge on the same concrete target.
- Plugin exclusivity requires an explicit operator grant and audit evidence bound to the plugin version, `bindingId`, target policy scope, and granting actor. Manifest intent alone grants no exclusivity.

Typed selector contracts:

- `GLOBAL` uses `{}` and is valid only for event types whose event registry marks global bindings as legal.
- `REGION` uses `{"regionTemplateId": "regionTemplateId:<stable-id>"}` and resolves against World Management region templates for the exact `baseVersionId`.
- `ENTITY_TEMPLATE` uses `{"entityTemplateId": "entityTemplateId:<stable-id>"}` and resolves against Entity Management entity templates for the exact `baseVersionId`.
- `COMMAND_ALIAS` uses `{"commandAlias": "<normalized-command-alias>"}`. In the initial slice this scope is valid only for aliases backed by the canonical built-in command registry; authored command namespaces are not yet part of the plugin-binding contract. Game Design must normalize command aliases using the same built-in command registry rules used by the runtime command parser and must reject aliases that collide with reserved commands or another binding in the same target scope.
- Future `targetScopeType` values must define their selector object, owner service, normalization rules, and exact validation API before they can appear in an attested manifest.

Validation responsibilities:

- Game Design validates that all declared bindings are structurally valid and resolvable against the targeted `baseVersionId`.
- Game Design validates that `entrypointGraphId` references a declared graph, `orderIndex` is in the bounded platform range, `requiresExclusiveEvent` has the required operator policy grant, and the typed `targetSelector` can be resolved by the owner service under the exact `baseVersionId`.
- Automation & Scripting consumes the validated binding set during activation and registry load; it must not invent additional bindings or infer missing targets from runtime state. The current activation path now re-checks built-in `COMMAND_ALIAS` bindings against Game Session's authoritative built-in command registry and rejects instance-scoped alias/exclusive-binding conflicts against the currently pinned script patch plus already-enabled plugins before mutating runtime state.

### Plugin Component Policy Management

Plugin component policy is managed centrally so operators can reason about which DSL components are allowed in plugins in each environment:

- The authoritative plugin allowlist lives in the Automation & Scripting Service (or a shared policy store) and is configured per environment (for example, development, staging, production).
- Operators can inspect the effective policy via an admin-only API such as `ListPluginComponentPolicies`, which returns the allowed and blocked component identifiers for a given environment or tenant. Logging & Admin surfaces this information in its UIs so platform owners can review policy before enabling new plugins.
- Policy changes are treated as configuration deployments: they are versioned, rolled out through existing deployment pipelines, and can be audited alongside other configuration changes.
- When policy is tightened, existing plugins that now reference blocked components are treated as invalid: their triggers are rejected with a dedicated outcome (for example, `plugin_component_blocked`) recorded in `script_event_audit` and surfaced via metrics so operators can see that enforcement, not quotas, is preventing execution.

For metrics and outcome naming conventions around plugin policy enforcement, see `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

#### Policy Rollout & Rollback

To avoid unintentionally breaking large numbers of plugins when component policies change, platform operators should roll out new policies in two phases:

- **Report-only phase** – a new policy version is loaded in a non-enforcing mode:
  - Policy violations are recorded as structured metadata on `script_event_audit` rows via `policyViolations[]` using the schema and size limits defined in `design/architecture/system-architecture-scripting-observability-contract.md`, and via `automation_plugin_policy_violations_total`, but plugin triggers are still admitted and executed.
  - In report-only mode, `policyViolations[].decision` must be `REPORT_ONLY`, and `finalOutcome` must remain the actual pipeline result (`success`, `sandbox_error`, `infrastructure_error`, and so on). `finalOutcome=plugin_component_blocked` is invalid while report-only mode is active.
  - Dashboards and alerts use these signals to show which plugins would be blocked if enforcement were enabled.
- **Enforcing phase** – once violations are understood and unacceptable plugins have been migrated or disabled:
  - Enforcement is enabled for the policy version; subsequent violations must set `policyViolations[].decision=BLOCKED` and cause triggers to be rejected at admission with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` that identifies the blocked component/policy decision.
  - Operators continue to monitor `automation_plugin_policy_violations_total` to detect regressions.

Example:

- In report-only mode, a plugin trigger that references a newly discouraged `world.admin.teleport` component may still execute and, when every required dispatch is accepted, finish with `finalOutcome=handoff_accepted`, while `policyViolations[]` records that component with `decision=REPORT_ONLY` and `automation_plugin_policy_violations_total` increments for the same component and policy version.
- In enforcing mode for that same policy version, the same trigger must stop at admission with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` that identifies the blocked `world.admin.teleport` component or policy decision. `policyViolations[]` then records `decision=BLOCKED`, and operators should see the same component reflected in `automation_plugin_policy_violations_total`.

Policy configs should be versioned so operators can roll back to a previous allowlist if enforcement causes unexpected disruption. Report-only and enforcing behavior are configuration choices on the policy version and must be applied consistently across environments as part of the normal deployment pipeline.

Operationally, Logging & Admin acts as the operator-facing orchestration and audit surface for plugin lifecycle management. The authoritative runtime control plane remains the Automation & Scripting APIs that own `activeVersionId`, `pendingVersionId`, and `pluginState`; Logging & Admin coordinates those APIs rather than owning a competing runtime registry contract.

To roll back a misbehaving plugin, operators must publish a new trusted `pluginVersionId` that reintroduces the desired logic, then promote that newly published version to `activeVersionId` for the affected `<tenantId, gameInstanceId, pluginId>` via Logging & Admin. Historical `SUPERSEDED` versions remain immutable audit records and are not reactivated. The Automation & Scripting Service then resumes admitting triggers for the restored logic version while continuing to enforce quotas, budgets, and sandbox limits as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

Plugin rollback/disable/revocation flows must also cancel pending outbox work for the displaced plugin version via `CancelPendingWorkItemsForPluginVersion` before or alongside queue purges, so stale plugin-version work cannot continue to hand off after control-plane changes.

Rollback/disable/revocation flows must also reconcile durable plugin-owned timers:

- Any timer or interval owned by the displaced `pluginVersionId` must be removed or tombstoned before normal scheduling resumes for that plugin.
- Canceling queued work items alone is insufficient; otherwise an old plugin version could continue minting new timer-driven triggers after disablement or rollback.
- A newer plugin version starts schedules fresh by default. The scheduler may carry an interval forward only when both versions explicitly declare compatible continuity for the same `pluginId`, `scheduleDefinitionId`, and target scope; reconciliation then rewrites exact provenance to the new `pluginVersionId` and recalculates the due point from the normative resume rule.

The normative control-plane API shapes and required events for plugin management are defined in `design/architecture/system-architecture-scripting-control-plane-api.md` (for example `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin`).
For operator verification during rollback, disablement, or signer revocation, use the same control-plane read surfaces that gate scripting convergence for the affected runtime scope, including plugin-state reads from `design/architecture/system-architecture-scripting-control-plane-api.md` and the scripting drain/convergence workflow reads delegated from `design/architecture/system-architecture-scripting-control-plane-operations.md`.

## Monitoring & Debugging

Plugin executions participate in the same observability pipeline as core scripts and use shared identifiers and metrics:

- Each plugin trigger is recorded in `script_event_audit` with the required Trigger Identity fields (including `tenantId`, `gameInstanceId`, and for gameplay/runtime triggers `regionEpoch`), plus `pluginId` / `pluginVersionId` and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`).
- Automation metrics such as:
  - `automation_script_triggers_total{scope, script_category, plugin_family, plugin_version_family, eventType, outcome}`
  - `automation_script_skips_total{scope, script_category, plugin_family, reason}`
  - `automation_script_triggers_dropped_total{scope, script_category, plugin_family, reason}`
  - `automation_script_sandbox_failures_total{scope, script_category, plugin_family, reason}`
  - `automation_script_runtime_seconds{scope, script_category, plugin_family, eventType}`
  expose plugin behavior alongside core automation.

Dashboards and Logging & Admin tooling should surface these identifiers so operators can:

- Filter by `pluginId` / `pluginVersionId` to inspect plugin-specific health.
- Jump from a player-visible tick log (using `scriptEventId` or `correlationId`) to matching plugin executions in `script_event_audit`.

For details on the metrics glossary and cross-service correlation, see `design/architecture/system-architecture-scripting-quotas-and-operations.md#auditability--metrics` and `design/architecture/system-architecture-scripting-quotas-and-operations.md#cross-service-correlation`.

## Related Documentation

- [Automation & Scripting Service](../automation-scripting-service/README.md)
- [Game Design Service Architecture](README.md)
- [User Journeys – Extensibility & External Tools](../../user-journeys-creators.md#8-extensibility--external-tools)
- [System Architecture – Scripting & Automation](../../system-architecture-scripting.md)
- [Asset Storage Setup](asset-storage.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
