# In-Game Modding and Plugin Framework

This document outlines the modding system that lets administrators extend a published game without republishing a full version.

Plugins use the same **component-based DSL** and sandbox as the Automation & Scripting Service so custom logic can be hot reloaded safely.
Management APIs for enabling or disabling plugins reside in the Logging & Admin Service.

Plugin bundles are uploaded through the Game Design Service and stored in the same asset repository used for other design files.

## Goals

- Enable runtime loading of approved plugins written in the same **component‑based** scripting DSL used for automation.
- Provide a secure sandbox so plugins cannot access unauthorized data or system resources.
- Allow plugins to hook into game events exposed by the Game Logic and World Management services.
- Isolate plugin enablement and data per `tenantId` and `gameInstanceId` so a tenant can run multiple instances with different plugin selections safely.
- Forward plugin execution metrics and error logs to the Logging & Admin Service for auditing.

## Trust Model & Roles

Plugins run with the same core execution model as other scripts but are subject to additional trust and governance requirements:

- Only appropriately privileged principals (for example, tenant administrators or platform operators, depending on environment configuration) may upload or enable plugins for a given `tenantId`.
- Plugin bundles must be **signed** by a trusted key before the Game Design Service accepts them; key management and allowed signers are environment-specific and controlled by platform operators.
- Different environments (development, staging, production) may use distinct signing keys and role policies so that development plugins cannot be promoted directly into production without review.

The Logging & Admin Service exposes management APIs for listing, enabling, disabling, and inspecting plugins; these APIs enforce the same role model and provide audit trails for all plugin state changes.

## Canonical Plugin Bundle Contract (Required)

Plugin bundles are not opaque archives. Authoring, validation, audit, and activation all consume one canonical signed contract inside the bundle.

Required bundle contents:

- `plugin-manifest.json` is the authoritative signed manifest for the bundle. Game Design and Automation & Scripting must derive activation metadata from this file rather than from upload-time form fields or object-store metadata.
- `signatures.json` contains the signature envelope described below.
- One or more DSL graph definition files referenced by `plugin-manifest.json`.
- Zero or more static assets referenced by `plugin-manifest.json`.

`plugin-manifest.json` must define at least:

- `pluginId` – stable logical plugin identity reused across versions.
- `pluginVersionId` – immutable version identity for this bundle only.
- `baseVersionId` – the exact published game version this plugin version targets.
- `abilitySchemaDigest` – the immutable ability schema digest for that same `baseVersionId`.
- `displayName` and optional human-facing description/version notes.
- `entrypoints[]` – the DSL graphs included in the bundle, with stable graph identifiers and file references.
- `bindings[]` – the event subscriptions and target scopes declared by the bundle, using the canonical binding model defined below.
- `requiredComponents[]` – DSL component identifiers the bundle requires so policy validation can be deterministic.
- `requiredCapabilities[]` – any elevated plugin capabilities if the platform introduces them later; empty in the initial slice unless explicitly used.
- `assetRefs[]` – optional plugin-local asset references.

Contract rules:

- `pluginVersionId` is immutable content identity. Republishing after any manifest, graph, binding, or asset change requires a new `pluginVersionId`.
- `baseVersionId` compatibility is exact, not fuzzy. A plugin version targets one published game version only.
- `abilitySchemaDigest` must match the immutable digest attested for that `baseVersionId`; it is recorded in Game Design metadata and re-checked at activation time.
- Game Design must persist indexed metadata from the signed manifest (`pluginId`, `pluginVersionId`, `baseVersionId`, `abilitySchemaDigest`, signer identity, validation status) so UIs and control-plane APIs do not need to unpack bundles for routine reads.
- Automation & Scripting must treat the signed manifest as the source of truth for runtime activation metadata. It may cache extracted fields, but it must not trust mutable side-channel metadata over the signed manifest.
- If `assetRefs[]` are runtime-consumable, runtime discovery must flow through the signed manifest metadata that Game Design publishes and, where instance/runtime consumers need object-store access, through the attested release/distribution metadata derived from that manifest rather than undocumented bucket key conventions.

## Signing and Key Lifecycle (Required)

Plugin bundle signing must be specified precisely enough that operators can rotate keys and revoke signers without ambiguity.

Minimum requirements:

- **Algorithm**: plugin bundles are signed using **Ed25519**.
- **Bundle digest**: each uploaded bundle computes a stable `bundleDigest` (for example SHA-256 over the canonical bundle bytes) which is the input to signature verification and is recorded in audit trails.
  - **Canonicalization (required)**: the bundle format must define “canonical bundle bytes” precisely so the same logical bundle always hashes the same:
    - Archive format is fixed (for example `tar` with deterministic headers, or `zip` with normalized timestamps).
    - File ordering is deterministic.
    - Timestamps/UID/GID/permissions are normalized or excluded from the digest input.
    - The bundle digest input must exclude any transport-layer wrapper (for example HTTP multipart boundaries).
- **Bundle ingestion safety limits (required)**:
  - Upload and extraction must enforce bounded limits before runtime validation (for example `maxBundleBytes`, `maxExpandedBytes`, `maxFileCount`, and `maxCompressionRatio`).
  - Extraction and manifest parsing must use bounded timeouts and memory limits; over-limit bundles must fail closed.
  - Limits and failures must be audit-visible with deterministic bounded reason codes (for example `bundle_too_large`, `bundle_compression_ratio_exceeded`, `bundle_file_count_exceeded`, `bundle_parse_timeout`).
- **Key identity**: every signature is tied to a `signerKeyId` (stable identifier for the public key used to verify the signature).
- **Signature envelope (required)**:
  - Bundles must contain a machine-readable signature manifest (for example `signatures.json`) that includes `bundleDigest`, `signerKeyId`, the `ed25519Signature` bytes, and an optional `signatureCreatedAt`.
  - Multiple signatures may be present; verification succeeds if at least one signature is by an allowlisted `signerKeyId` and none are by explicitly revoked keys.
- **Verification points**:
  - Game Design verifies the signature at upload time and records `bundleDigest`, `signerKeyId`, and `signatureVerifiedAt`.
  - Automation & Scripting re-verifies signatures at load/activation time (defense in depth) and rejects activation if verification fails or the signer is not allowed for the environment.
- **Rotation**:
  - Operators can introduce new signer keys without downtime by adding a new `signerKeyId` to the allowlist for the environment.
  - Old keys may remain valid for existing bundles during a transition window, but new uploads should prefer the newest active key.
- **Revocation**:
  - Operators can revoke a signer by removing its `signerKeyId` from the allowlist and adding it to a revocation list.
  - When a signer is revoked, subsequent loads/activations of bundles signed by that key must fail, and already-enabled plugins must transition to `pluginState=DISABLED` with mandatory `statusReason=signer_revoked`; triggers are rejected and the reason is recorded in `script_event_audit`.
- **Propagation (required)**:
  - The allowlist and revocation list must be distributed to runtime services as a signed configuration artifact with a bounded refresh interval.
  - Automation & Scripting must refresh signer policy on a bounded cadence (for example every 60 seconds) and must disable affected plugins within a fixed operator SLO (for example “revocation disables affected plugins within 5 minutes”).
  - Disablement due to revocation must emit an operator-visible control-plane event and be visible in audit tooling so operators can prove when revocation took effect.
  - Runtime signer-policy visibility must be queryable via control-plane read APIs (for example `GetSignerPolicyConvergence`) so operators can verify policy propagation before and after revocation. Before resuming normal admission after a revocation or policy repair, operators should use those read surfaces together with the scripting control-plane convergence/drain reads for the affected scope to confirm that policy propagation, plugin disablement, and any required draining have all converged.
  - If signer policy cannot be refreshed/verified for a scope beyond max-age, plugin admission must fail closed with `finalOutcome=signer_policy_unavailable` until policy converges.
  - Any override that permits plugin admission while signer policy is unavailable must be explicit, time-bounded, scoped, and operator-audited, and must auto-expire back to fail-closed mode.

Logging & Admin must surface signer identity and verification status (including `bundleDigest` and `signerKeyId`) so operators can explain why a plugin version was accepted or rejected.
Logging & Admin must also surface signer-policy propagation status and revocation application events (for example `SignerPolicyVersionObserved` and `SignerRevocationApplied`) so operators can prove enforcement timing across services.

## Outline

1. Plugins are packaged as signed bundles uploaded through the Game Design Service.
2. The Game Design Service validates each bundle's signature before storing it in the asset repository so versions can be tracked. See [Asset Storage Setup](asset-storage.md).
3. The Automation & Scripting Service executes plugin code with strict quotas similar to regular scripts.
4. A registry tracks which plugins are active for each game instance and exposes toggle APIs via the Logging & Admin Service.
5. Plugins can subscribe to events such as `onEnterRoom` or `onItemUse` to inject custom behavior.
6. Execution metrics and error logs are forwarded to the Logging & Admin Service for monitoring.
7. Plugin bundles are versioned as design assets but are independently publishable and operator-activatable against exactly one already published `baseVersionId`; a new game-version publish requires a new plugin version whenever the plugin must target a different `baseVersionId` or `abilitySchemaDigest`.

## Sandbox Capabilities & Quotas

At runtime, plugins use the same **component-based scripting DSL and sandbox** as the Automation & Scripting Service:

- Plugin graphs are authored from a curated set of DSL components. Platform owners may choose to expose the full component set or a restricted subset for plugins (for example, disallowing certain world-generation or administrative components) by configuration and policy.
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

From a validation perspective, plugins follow the same core rules as regular scripts, with additional restrictions driven by their tighter trust model:

- The Game Design Service applies the same **graph validation and loop safety analysis** to plugin graphs as it does to regular scripts. Unsafe graphs (for example, unbounded cycles without guard nodes) are rejected and must be fixed before a plugin version can be enabled.
- Platform owners may maintain a **plugin-specific allowlist** of components. Components that are safe for core automation but not for plugins (for example, administrative or world-generation primitives) are either hidden in plugin editors or treated as `UNSAFE` in plugin contexts and must be removed before publication.
- Deprecation and migration flows apply equally: plugins using deprecated or unsafe components appear in “requires migration” views and are not eligible to run until migrated and republished.

Validation results for plugins surface through the same tooling as core scripts (Game Design and Logging & Admin UIs), but may include plugin-specific reason codes so operators can distinguish “invalid graph” from “disallowed component for plugin use”.

## Design-Time Plugin Version Lifecycle

Game Design owns the authoring and publication lifecycle for plugin versions before any instance-scoped activation occurs.

Each `(tenantId, pluginId, pluginVersionId)` must have one canonical design-time status:

- `DRAFT` – authoring metadata exists but no accepted signed bundle has been stored.
- `UPLOAD_REJECTED` – bundle ingestion failed before publication, for example due to archive safety limits, malformed manifest, or signature failure.
- `SIGNATURE_VERIFIED` – the bundle passed canonicalization and signature verification and its signed metadata has been persisted. This is a durable operator-visible state, not merely an internal transient step; a version may remain here indefinitely until publication is requested or abandoned.
- `VALIDATION_FAILED_DESIGN` – Game Design completed design-time validation and rejected the version due to deterministic authoring errors such as invalid bindings, disallowed components, `baseVersionId` mismatch, or `abilitySchemaDigest` mismatch.
- `PUBLISHED` – the plugin version is accepted into immutable design-time history and is eligible for runtime activation against matching instances.
- `SUPERSEDED` – a later plugin version for the same `pluginId` exists; older versions remain immutable historical records and are not eligible for runtime activation.

Required lifecycle semantics:

- Only `PUBLISHED` plugin versions are eligible inputs to runtime activation APIs such as `SetPluginActiveVersion`.
- Transitioning a plugin version to `SUPERSEDED` removes it from the set of activatable versions. If operators need to return to equivalent plugin logic later, they must publish a new `pluginVersionId` rather than reactivating the superseded historical version.
- `UPLOAD_REJECTED` and `VALIDATION_FAILED_DESIGN` are design-time terminal failures and must not create or mutate runtime registry rows.
- Transition into `PUBLISHED` records the indexed manifest metadata, validation results, signer metadata, and publication timestamp in Game Design.
- Logging & Admin may inspect all design-time states, but it must not bypass them by attempting to activate a non-`PUBLISHED` plugin version.
- Game Design must expose read surfaces such as `GetPluginVersionStatus(tenantId, pluginId, pluginVersionId)` and `ListPluginVersionStatuses(tenantId, pluginId?)`, plus a durable `PluginVersionStatusChanged` event family, so authoring UIs and operator tooling read one authoritative publication state model.

Required write path:

- `UploadPluginBundle` accepts the bundle bytes, performs archive safety checks, canonicalization, signature verification, manifest extraction, and indexed metadata persistence.
  - Success moves the version to `SIGNATURE_VERIFIED`.
  - Deterministic ingestion or signature failures move the version to `UPLOAD_REJECTED`.
- `PublishPluginVersion` is the explicit design-time publication step for a previously uploaded bundle version.
  - It runs design-time validation over graphs, bindings, component policy, `baseVersionId`, and `abilitySchemaDigest`.
  - Validation failures move the version to `VALIDATION_FAILED_DESIGN`.
  - Success moves the version to `PUBLISHED` and emits `PluginVersionStatusChanged`.
- Re-invoking either call with the same `(tenantId, pluginId, pluginVersionId)` must be idempotent: once a version is immutable, callers may observe the existing status but must not overwrite signed content in place.

### Minimal Bundle Example

This example shows the minimum signed authoring shape expected by the publication pipeline:

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
      "eventType": "onEnterRegion",
      "targetScopeType": "REGION",
      "targetSelector": "market-square",
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

- `UploadPluginBundle` verifies the archive, signatures, and manifest shape, then persists indexed metadata for `town-crier-v3`.
- `PublishPluginVersion` validates that `market-square` exists in `game-v12`, that `announce-arrival` is present and safe, and that the plugin remains compatible with `sha256:9dd1b7c2...`.
- `SetPluginActiveVersion` may later activate `town-crier-v3` only for instances whose `runtimeVersionId` is `game-v12` and whose bound ability schema digest matches the same value.
- If `assetRefs[]` included an entry such as `"assetRefs": [{"assetId": "bell-sfx", "path": "assets/bell.ogg"}]`, Game Design would persist that signed reference as indexed bundle metadata and expose the runtime-discoverable asset only through attested release/distribution metadata derived from the signed manifest. Runtime consumers would resolve `bell-sfx` through that attested metadata, not by constructing object-store paths directly.

### End-to-End Publication Sequence

One canonical happy path plus failure path:

1. A creator uploads `town-crier-v3` with `UploadPluginBundle`.
2. Game Design enforces archive safety limits, verifies signatures, extracts `plugin-manifest.json`, persists indexed metadata, and sets status to `SIGNATURE_VERIFIED`.
3. A creator or operator invokes `PublishPluginVersion` for the same `(tenantId, pluginId, pluginVersionId)`.
4. Game Design validates graphs, bindings, component policy, `baseVersionId`, and `abilitySchemaDigest`.
5. If validation succeeds, Game Design sets status to `PUBLISHED` and emits `PluginVersionStatusChanged`.
6. Logging & Admin may then invoke `SetPluginActiveVersion` for a matching game instance; Automation & Scripting admits the plugin only if the instance `runtimeVersionId` and ability digest match the published metadata.

Failure example:

1. `UploadPluginBundle` succeeds and leaves the version in `SIGNATURE_VERIFIED`.
2. `PublishPluginVersion` discovers that binding target `market-square` does not exist in the plugin's exact `baseVersionId`.
3. Game Design sets status to `VALIDATION_FAILED_DESIGN`, emits `PluginVersionStatusChanged`, and does not create or mutate runtime registry state.
4. Any later `SetPluginActiveVersion` attempt for that `pluginVersionId` must fail deterministically because the version is not `PUBLISHED`.

## Plugin Lifecycle & Rollback

Plugins follow a lifecycle similar to script patches but scoped to `<tenantId, gameInstanceId, pluginId>` so a tenant can run multiple game instances with different plugin selections safely.

- Plugins do **not** participate in the script-patch `onLoad` lifecycle. There is no plugin-scoped `onLoad` or `onUnload` contract in the first implementation slice.
- Plugin activation therefore consists only of:
  - signature and policy verification,
  - graph validation and compatibility checks,
  - loading the new plugin version into the instance-scoped runtime registry, and
  - reconciling any plugin-owned derived scheduler state such as timers.
- Any plugin setup that would otherwise require startup code must be expressed through normal event handlers (`onSpawn`, `onEnterRegion`, `onInterval`, custom events) or explicit operator/admin workflows. Implementations must not invent an implicit plugin initialization hook.

Design-time publication and runtime activation are separate:

- Publication in Game Design means the plugin bundle is immutable, signed, validated, and available for activation.
- Activation in Automation & Scripting means a `PUBLISHED` plugin version has been selected for one `(tenantId, gameInstanceId, pluginId)` and admitted into the runtime registry.
- A plugin version that is `PUBLISHED` in Game Design may still be `DISABLED` or never activated for any instance.

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

Plugin bindings are authored as signed design-time data, not as ad hoc instance-local toggles.

- The authoritative `bindings[]` array lives in `plugin-manifest.json` and is part of the signed bundle.
- Each binding must declare:
  - `bindingId` – stable identity within the plugin version.
  - `eventType` – the subscribed event or timer kind.
  - `targetScopeType` – one of `GLOBAL`, `REGION`, `ENTITY_TEMPLATE`, `COMMAND_ALIAS`, or another explicitly documented scope added later.
  - `targetSelector` – the identifier or selector for that scope.
  - `entrypointGraphId` – which declared graph handles the binding.
  - Any bounded binding parameters needed for validation, such as interval cadence.
- Binding targets must reference published base-version assets that exist under the plugin version’s exact `baseVersionId`.
- Rebinding is a content change. Adding, removing, or retargeting any binding requires publishing a new `pluginVersionId`; instance activation only chooses among published plugin versions and does not edit bindings in place.
- Instance-scoped enablement remains separate from binding definition: Logging & Admin chooses whether a published plugin version is active for a given game instance, but it does not rewrite the signed binding set during activation.

Validation responsibilities:

- Game Design validates that all declared bindings are structurally valid and resolvable against the targeted `baseVersionId`.
- Automation & Scripting consumes the validated binding set during activation and registry load; it must not invent additional bindings or infer missing targets from runtime state.

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

- In report-only mode, a plugin trigger that references a newly discouraged `world.admin.teleport` component may still execute and finish with `finalOutcome=success`, while `policyViolations[]` records that component with `decision=REPORT_ONLY` and `automation_plugin_policy_violations_total` increments for the same component and policy version.
- In enforcing mode for that same policy version, the same trigger must stop at admission with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` that identifies the blocked `world.admin.teleport` component or policy decision. `policyViolations[]` then records `decision=BLOCKED`, and operators should see the same component reflected in `automation_plugin_policy_violations_total`.

Policy configs should be versioned so operators can roll back to a previous allowlist if enforcement causes unexpected disruption. Report-only and enforcing behavior are configuration choices on the policy version and must be applied consistently across environments as part of the normal deployment pipeline.

Operationally, the **Logging & Admin Service** acts as the control plane for plugin lifecycle management. Enabling, disabling, draining, and rolling back plugin versions are all performed via Logging & Admin APIs that update the registry in the Automation & Scripting Service; tenants do not manipulate `activeVersionId` or `pluginState` directly inside game traffic.

To roll back a misbehaving plugin, operators promote a previously trusted `pluginVersionId` to `activeVersionId` for the affected `<tenantId, gameInstanceId, pluginId>` via Logging & Admin. The Automation & Scripting Service then resumes admitting triggers for the restored version while continuing to enforce quotas, budgets, and sandbox limits as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

Plugin rollback/disable/revocation flows must also cancel pending outbox work for the displaced plugin version (for example via `CancelPendingWorkItemsForPluginVersion`) before or alongside queue purges, so stale plugin-version work cannot continue to hand off after control-plane changes.

Rollback/disable/revocation flows must also reconcile durable plugin-owned timers:

- Any timer or interval owned by the displaced `pluginVersionId` must be removed or tombstoned before normal scheduling resumes for that plugin.
- Canceling queued work items alone is insufficient; otherwise an old plugin version could continue minting new timer-driven triggers after disablement or rollback.
- If a newer plugin version preserves the same schedule, the scheduler may carry it forward only when the old and new definitions share the same stable `scheduleDefinitionId`; reconciliation must then explicitly rewrite ownership to the new `pluginVersionId`.

The normative control-plane API shapes and required events for plugin management are defined in `design/architecture/system-architecture-scripting-control-plane-api.md` (for example `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin`).
For operator verification during rollback, disablement, or signer revocation, use the same control-plane read surfaces that gate scripting convergence for the affected runtime scope, including plugin-state reads and the scripting drain/convergence APIs delegated from `design/architecture/system-architecture-scripting-control-plane-api.md`.

## Monitoring & Debugging

Plugin executions participate in the same observability pipeline as core scripts and use shared identifiers and metrics:

- Each plugin trigger is recorded in `script_event_audit` with the required Trigger Identity fields (including `tenantId`, `gameInstanceId`, and for gameplay/runtime triggers `regionEpoch`), plus `pluginId` / `pluginVersionId` and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`).
- Automation metrics such as:
  - `automation_script_triggers_total{tenantId, scriptId, pluginId, pluginVersionId, eventType, outcome}`
  - `automation_script_skips_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_triggers_dropped_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_sandbox_failures_total{tenantId, scriptId, pluginId, reason}`
  - `automation_script_runtime_seconds{tenantId, scriptId, pluginId, eventType}`
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
