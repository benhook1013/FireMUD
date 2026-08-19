# In-Game Modding and Plugin Framework

## Target State

Game Design publishes immutable plugin versions with compatibility and provenance evidence; Automation & Scripting owns plugin readiness, activation, and runtime lifecycle; and Game Session remains authoritative for the exact script-pin tuple that fences runtime work. The broader target provenance contract permits operator-approved unsigned packages only after exact digest, complete validation, scoped approval, and platform acceptance attestation ([ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md)); current implementation and hosted policy remain signed-only.

## Implementation Status

The current implementation and hosted policy support signed-only plugin intake and activation after allowlisted Ed25519 verification. The operator-permitted unsigned provenance flow in [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md) is target-only, and complete runtime tuple/plugin-epoch fencing proof remains target-state and incomplete; the detailed local boundaries below and the linked scripting contracts remain authoritative.

This document outlines the modding system that lets administrators extend a published game without republishing a full version.

Plugins use the same **component-based DSL** and sandbox as the Automation & Scripting Service so custom logic can be hot reloaded safely.
Operator-facing plugin lifecycle UX is surfaced through Logging & Admin, but the authoritative runtime mutation APIs (`GetPluginStatus`, `SetPluginActiveVersion`, `DisablePlugin`, `DrainPlugin`) are owned by Automation & Scripting as described in the scripting control-plane docs.

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
- The current implementation and initial hosted policy accept plugin bundles only after allowlisted **Ed25519** signature verification; key management and allowed signers are environment-specific and controlled by platform operators.
- Different environments (development, staging, production) may use distinct signing keys and role policies so that development plugins cannot be promoted directly into production without review.

The Logging & Admin Service exposes management APIs for listing, enabling, disabling, and inspecting plugins; these APIs enforce the same role model and provide audit trails for all plugin state changes.

[ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md) records a broader target provenance contract: an operator-permitted unsigned package may be accepted only after exact digest, complete validation, explicit scoped approval, and platform acceptance attestation. Hosted policy may prohibit unsigned intake entirely, and this unsigned path is not implemented in the current signed-only upload and activation flow.

## Initial Authoring Mode

The initial plugin model is external signed bundles only:

- creators or operators assemble plugin bundles outside the product;
- Game Design accepts upload, verifies signatures, inspects indexed metadata, validates publication rules, and records immutable design-time history; and
- Game Design does not provide an in-product editor that mutates plugin bundle internals or re-signs bundles on behalf of authors in the initial slice.

Stable `graphId`, `bindingId`, `pluginId`, and `pluginVersionId` values therefore originate in the signed bundle authored outside the product. Any future in-product draft/export workflow would require its own design update and must not be inferred from the initial external-bundle model.

## Canonical Plugin Bundle Contract (Current signed intake)

Plugin bundles are not opaque archives. Authoring, validation, audit, and activation all consume one canonical signed contract inside the bundle.

Required bundle contents:

- `plugin-manifest.json` is the authoritative signed manifest for the bundle. Game Design and Automation & Scripting must derive activation metadata from this file rather than from upload-time form fields or object-store metadata.
- `signatures.json` contains the signature envelope described below.
- One or more DSL graph definition files referenced by `plugin-manifest.json`.
- Zero or more static assets referenced by `plugin-manifest.json`.

`plugin-manifest.json` must define at least:

- `schemaVersion` – required signed manifest schema version; unsupported schema versions fail closed at upload/publish/activation.
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

Signed `assetRefs[]` entries must define at minimum:

- `assetId` – stable identifier unique within the plugin version.
- `path` – canonical bundle-relative path.
- `contentHash` – immutable digest of the referenced asset bytes.
- `contentType` – media type.
- optional bounded metadata such as `sizeBytes`, localization tag, or usage hint.

Contract rules:

- `pluginVersionId` is immutable content identity. Republishing after any manifest, graph, binding, or asset change requires a new `pluginVersionId`.
- Signature-only approval changes do not require a new `pluginVersionId` when the signed payload bytes are identical. The same immutable plugin version may therefore carry multiple accepted signatures or environment approvals over time, but any manifest/graph/binding/asset-byte change still requires a new `pluginVersionId`.
- `baseVersionId` compatibility is exact, not fuzzy. A plugin version targets one published game version only.
- `abilitySchemaDigest` must match the immutable digest attested for that `baseVersionId`; it is recorded in Game Design metadata and re-checked at activation time.
- **Target state:** Game Design must persist indexed metadata from the signed manifest (`pluginId`, `pluginVersionId`, `baseVersionId`, `abilitySchemaDigest`, the complete canonically ordered verified signature set, validation status) so UIs and control-plane APIs do not need to unpack bundles for routine reads.
- When `assetRefs[]` is non-empty, Game Design must also persist `distributionManifestHash` and `distributionManifestPath` for the plugin-version distribution manifest it writes during `PublishPluginVersion`.
- Automation & Scripting must treat the signed manifest as the source of truth for runtime activation metadata. It may cache extracted fields, but it must not trust mutable side-channel metadata over the signed manifest.
- If `assetRefs[]` are runtime-consumable, runtime discovery must flow through the signed manifest metadata that Game Design publishes and, where instance/runtime consumers need object-store access, through the attested release/distribution metadata derived from that manifest rather than undocumented bucket key conventions.

### Plugin Asset Distribution

Plugin assets are distributed through a plugin-version-scoped manifest, not through the base game version's `published_release_bundle`. A plugin version targets one already published `baseVersionId`, but publishing the plugin must not mutate the immutable release attestation for that base version.

When `assetRefs[]` is empty, no plugin distribution manifest is required. When `assetRefs[]` is non-empty, `PublishPluginVersion` must write a `plugin-distribution-manifest.json` under a Game Design-owned object-store prefix scoped to `(tenantId, pluginId, pluginVersionId)`, persist its hash in indexed plugin metadata, and expose it through `GetPublishedPluginVersion` / `ListPluginVersionStatuses`. Runtime consumers resolve plugin assets only through that metadata and the signed `assetRefs[]`; they must not construct object-store paths from tenant or plugin identifiers.

The distribution manifest must include:

- `tenantId`, `pluginId`, `pluginVersionId`, `baseVersionId`, and `abilitySchemaDigest`.
- `manifestHash` and `manifestSchemaVersion`.
- **Target state:** `bundleDigest` and the immutable publication-time/base verified-signature evidence set bound to that digest. Post-publication signature-only approvals do not version or rewrite this manifest; target complete signature reads compose the base set with the append-only Game Design evidence ledger.
- `assets[]` entries keyed by signed `assetId`, with canonical object-store URL or opaque storage key, content hash, media type, byte size, and optional localization or usage metadata.

`PublishPluginVersion` must fail before `PUBLISHED` if any signed `assetRefs[]` entry is missing from the bundle, cannot be exported, has a digest mismatch, or cannot be represented in the distribution manifest. Exact-byte repair rules mirror version asset repair: a published plugin distribution manifest is immutable, and repair may only reproduce bytes that match the persisted manifest hash.

## Signing and Key Lifecycle (Required)

Plugin bundle signing must be specified precisely enough that operators can rotate keys and revoke signers without ambiguity.

The live signed-only intake currently accepts signature envelopes with multiple entries, but selects the first valid allowlisted signer in envelope order and persists/exposes only that `signerKeyId`; it does not enforce a revoked non-selected signer. Complete-set persistence, canonical ordering, all-signer revocation, and complete-set reads remain target-only pending the evidence ledger, storage, RPC, and signer-policy propagation. The signed-intake v1 digest and signature encoding below are canonical now; the ADR 0111 unsigned provenance variant is also target-only and neither path relaxes the live verification and activation checks.

Minimum requirements:

- **Algorithm**: plugin bundles are signed using **Ed25519**.
- **Bundle digest (signed-intake v1)**: `bundleDigest` is SHA-256 over every extracted bundle entry except `signatures.json`, ordered by Java `String` natural order (UTF-16 code-unit order) of the entry path. For each entry, the hash input appends the UTF-8 path bytes, one NUL byte, the raw file bytes, and one NUL byte. The digest is rendered as lowercase hexadecimal; archive and transport-wrapper metadata do not participate.
- **Bundle ingestion safety limits (required)**:
  - Upload and extraction must enforce bounded limits before runtime validation (for example `maxBundleBytes`, `maxExpandedBytes`, `maxFileCount`, and `maxCompressionRatio`).
  - Extraction and manifest parsing must use bounded timeouts and memory limits; over-limit bundles must fail closed.
  - Limits and failures must be audit-visible with deterministic bounded reason codes (for example `bundle_too_large`, `bundle_compression_ratio_exceeded`, `bundle_file_count_exceeded`, `bundle_parse_timeout`).
- **Key identity**: every signature is tied to a `signerKeyId` (stable identifier for the public key used to verify the signature).
- **Signature envelope (required)**:
  - Bundles must contain a machine-readable signature manifest (for example `signatures.json`) whose entries include the lowercase-hex `bundleDigest`, `signerKeyId`, the standard-Base64 `ed25519Signature`, and an optional `signatureCreatedAt`. In signed-intake v1, Ed25519 signs and verifies the UTF-8 bytes of the lowercase-hex `bundleDigest`; configured public-key values are standard Base64 of X.509 SubjectPublicKeyInfo bytes.
  - Multiple signatures may be present; Game Design persists the complete canonically ordered verified signature set bound to the bundle digest and must not select or persist one preferred signer. The set permits at most one entry per `signerKeyId`; duplicate entries are rejected. Verified entries are ordered ascending by the canonical UTF-8 byte representation of each validated opaque `signerKeyId`, with no locale collation, case normalization, or alternate normalization. Verification succeeds only when at least one signature is by an allowlisted signer and no signature is by an explicitly revoked signer.
- **Verification points**:
  - **Target state:** Game Design verifies the complete signature set at upload time and records it with `bundleDigest` and `signatureVerifiedAt`; current signed-intake v1 applies the canonical format above but persists and exposes only one allowlisted signer.
  - **Target state:** Automation & Scripting re-verifies the complete signature set and revalidates current signer, component, and capability policy at load/activation time and on resume/recovery (defense in depth), rejecting activation or resumption if verification fails or current policy does not allow the bundle.
- **Rotation**:
  - Operators can introduce new signer keys without downtime by adding a new `signerKeyId` to the allowlist for the environment.
  - Old keys may remain valid for existing bundles during a transition window; key rotation must not reduce a bundle's persisted evidence to one preferred signer.
- **Revocation**:
  - Operators can revoke a signer by removing its `signerKeyId` from the allowlist and adding it to a revocation list.
  - When a signer is revoked, subsequent loads/activations of any bundle whose complete signature set contains that key must fail, and already-enabled plugins must transition to `pluginState=DISABLED` with mandatory `statusReason=signer_revoked`; triggers are rejected and the reason is recorded in `script_event_audit`.
- **Propagation (required)**:
  - The allowlist and revocation list must be distributed to runtime services as a signed configuration artifact with a bounded refresh interval.
- Automation & Scripting must refresh signer policy on a bounded cadence (for example every 60 seconds) and must disable affected plugins within a fixed operator SLO (for example “revocation disables affected plugins within 5 minutes”). The current Automation runtime includes a scheduled plugin-policy reconciliation sweep over enabled plugin runtime states; it disables active plugins when Game Design publication metadata reports signer revocation, blocked/missing component policy, unavailable policy metadata, or a no-longer-published plugin version.
  - Disablement due to revocation must emit an operator-visible control-plane event and be visible in audit tooling so operators can prove when revocation took effect.
  - Runtime signer-policy visibility must be queryable via control-plane read APIs (for example `GetSignerPolicyConvergence`) so operators can verify policy propagation before and after revocation. Before resuming normal admission after a revocation or policy repair, operators should use those read surfaces together with the scripting control-plane convergence/drain reads for the affected scope to confirm that policy propagation, plugin disablement, and any required draining have all converged.
  - If signer policy or its bundle evidence cannot be refreshed/verified for a scope beyond max-age, plugin admission must fail closed with `signer_policy_unavailable` until policy converges; the stage-specific response/audit field remains owned by the [scripting outcome contracts](../../system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes).

Logging & Admin must surface the complete canonically ordered verified signature set and verification status (including `bundleDigest` and each `signerKeyId`) so operators can explain why a plugin version was accepted or rejected.
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
  - Cluster-level automation ceilings and the aggregate automation tick budget, as described in the [scripting quota lifecycle](../../system-architecture-scripting-quotas-and-operations.md#budget-accounting-rules). Event-scope candidate admission creates no handler charge. After binding resolution, each resolved handler creates or reuses the quota owner's durable full-Trigger-Identity handler usage-charge record; execution later acquires the separately fenced, reclaimable capacity lease. Plugin definitions inherit the resulting bounded admission/throttle outcomes; the lease is distinct from the sandbox's per-run wall-clock timeout.

Live plugin executions (`isDryRun=false`) appear in `script_event_audit` with the same identifiers as regular live scripts, plus plugin-specific metadata, and contribute to the corresponding live automation metrics, enabling operators to monitor plugin behavior without a separate observability pipeline. Preview/test executions use the isolated preview or test result/audit surface and test-only metric families; they do not write live execution outcomes or increment live metric families.

The shared DSL and sandbox apply to embedded scripts and linked plugins; this service's local consequence is separate immutable plugin publication/compatibility evidence and `pluginId`/`pluginVersionId` identity. Automation & Scripting owns runtime activation and readiness; Game Session owns the exact `(scriptPatchVersion, scriptPinEpoch)` pin and append-only rollout history. See the [scripting contracts](../../system-architecture-scripting-contracts.md), [control-plane API](../../system-architecture-scripting-control-plane-api.md), and [rollout and rollback](../../system-architecture-scripting-rollout-and-rollback.md) owners; [ADR 0111](../../decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md) preserves the distinct lifecycle boundary. Interval continuity remains an explicit opt-in under [ADR 0110](../../decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), not an inference from matching plugin names or `scheduleSemanticsHash`: carry-forward requires declarations on both sides, the stable logical key, and typed compatibility for kind/cadence, trigger conditions, handler identity, target and playable scope, dry-run namespace, owner, binding identity/compatibility, and runtime fences.

### Timer & Event Guarantees

Plugins share the same **event and timer semantics** as core scripts:

The exact Trigger Identity, evaluated-descriptor recovery, Command-Handoff Identity, and plugin tuple/fence guarantees below are target-state contracts; current implementation and proof status are tracked in the [Automation and Scheduler Runtime tracker](../../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status). This target contract is separate from the current signed bundle intake and publication path described above; signed verification remains the live admission boundary.

- Each plugin trigger has one logical evaluation per full applicable Trigger Identity. Before `EVALUATED_COMMITTED`, infrastructure recovery may re-enter the DSL only under that same identity and must converge on the same durable work item and command-child identities; after that boundary, recovery replays durable descriptors without re-entering the graph. `scriptEventId` is one field of Trigger Identity, not the complete key. Each handler invocation has one `script_event_audit` row; command attempts and outcomes are child handoff records keyed by the complete Command-Handoff Identity.
- Recurring and advisory plugin timers such as `onInterval` (or an advisory `onTimerExpire`) are **best-effort** under their declared recovery policy. Individual firings may be skipped or delayed when per-script quotas, per-tenant budgets, or cluster ceilings are reached, and skipped firings are not backfilled beyond bounded `COALESCE_ONE` behavior. A correctness-bearing one-shot timer instead persists durable intent outside Redis; physical execution may be at least once and replay-safe, while the same logical identity converges to one logical terminal outcome.
- Recurring/advisory plugin logic must therefore tolerate missed or delayed events (for example, by recomputing from current world state rather than assuming every interval executed). Every emitted command remains deduplicated and replay-selectable at child handoff under the complete Command-Handoff Identity: complete source runtime scope, optional `targetGameInstanceId`, `targetPlayableStateScope`, `targetRegionId`, and `targetRegionEpoch` only when the command targets a distinct runtime scope, persisted `automationDispatchId`, and deterministic `commandOrdinal`; same-instance handoffs omit every optional target field. The complete parent Trigger Identity, including plugin `bindingId` when applicable, is retained for correlation only. Downstream authoritative effect idempotency instead uses the stable root `EffectId`, typed operation, and exact target aggregate, with the immutable request digest stored and compared; a conflicting operation, target, or digest fails closed. `automationDispatchId` is reused across retries but is not globally unique, and `scriptEventId` alone is insufficient for fan-out commands; follow the [canonical scripting contract tables](../../system-architecture-scripting-normative-contract-tables.md).
- Before plugin work is evaluated and again before handoff, Automation revalidates the captured exact Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple against current authoritative state for the same `(tenantId, gameInstanceId)` runtime scope, together with the exact `(pluginId, pluginVersionId, bindingId)` and scoped Automation-owned `(pluginActivationEpoch, lifecycleRevision)` fence; see [Scripting Contracts §8](../../system-architecture-scripting-contracts.md#8-plugin-version-fencing-and-control-plane-scope). Already-terminal work is unaffected; no new admission proceeds without fresh signer-policy and Game Session authority, and immediately before any final side effect the applicable execution owner revalidates the exact plugin provenance, current activation/lifecycle, component policy, capability grant, signer/publication evidence, `(pluginActivationEpoch, lifecycleRevision)`, and Game Session tuple. Any mismatch or unavailable authority fails closed under the stage-specific dispositions below. Specifically unavailable signer policy/evidence retains `signer_policy_unavailable` on its stage-specific response/audit surface, while an unavailable authoritative runtime/fence read keeps unresolved work fenced and retryable as `authority_unavailable`. A present-but-different activation epoch fails closed with `plugin_activation_epoch_mismatch`; a proven plugin tuple/binding or lifecycle-evidence mismatch uses `plugin_binding_mismatch`; signer or revocation mismatches remain durably terminal/ineligible with their specific canonical dispositions. See [Scripting Runtime Execution](../../system-architecture-scripting-runtime-execution.md#version-fencing-and-rollback-safety), the [scripting outcome contracts](../../system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes), and the [Command-Handoff Identity](../../system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state) owner contracts.

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

- `DRAFT` – authoring metadata exists, but no package has been accepted and no provenance record has been accepted. For target unsigned intake, upload remains `DRAFT` until `PublishPluginVersion` records the exact digest, complete validation, scoped approval, and platform acceptance attestation; it then transitions to `PUBLISHED`. No unsigned-specific pre-publication status exists.
- `UPLOAD_REJECTED` – package ingestion failed before publication, for example due to archive safety limits, malformed manifest, or (for signed intake) signature failure.
- `SIGNATURE_VERIFIED` – signed-intake only: the bundle passed canonicalization and signature verification and its signed metadata has been persisted. This is a durable operator-visible state, not merely an internal transient step; a version may remain here indefinitely until publication is requested or abandoned. The target unsigned provenance path does not claim signature verification or introduce an unsigned-specific state.
- `VALIDATION_FAILED_DESIGN` – Game Design completed design-time validation and rejected the version due to deterministic authoring errors such as invalid bindings, disallowed components, `baseVersionId` mismatch, or `abilitySchemaDigest` mismatch.
- `PUBLISHED` – the plugin version is accepted into immutable design-time history after its applicable immutable provenance and validated publication evidence is recorded, and is eligible to be selected as input to a scoped runtime activation request; publication alone does not authorize runtime activation or admission.
- `SUPERSEDED` – a later plugin version for the same `pluginId` exists; older versions remain immutable historical records and are not eligible for runtime activation.
- `REVOKED_DESIGN` – the previously published design artifact remains historically readable, but signer revocation or a design-time trust decision has made the version ineligible for further runtime activation.

Required lifecycle semantics:

- Only `PUBLISHED` plugin versions are eligible inputs to runtime activation APIs such as `SetPluginActiveVersion`; Automation still decides runtime eligibility through current scoped activation, readiness, component-policy, capability, publication, and exact-fence checks.
- Transitioning a plugin version to `SUPERSEDED` removes it from the set of activatable versions. If operators need to return to equivalent plugin logic later, they must publish a new `pluginVersionId` rather than reactivating the superseded historical version.
- `UPLOAD_REJECTED` and `VALIDATION_FAILED_DESIGN` are design-time terminal failures and must not create or mutate runtime registry rows.
- Transition into `PUBLISHED` records indexed manifest metadata, validation results, applicable immutable provenance evidence, and publication timestamp in Game Design; signed bundles include signer metadata, while the ADR 0111 target unsigned path records the exact digest, complete validation, explicit scoped approval, and platform acceptance attestation.
- `RevokePluginVersion` is Game Design's publication mutation into `REVOKED_DESIGN`: it preserves the immutable, readable publication history and emits the append-only publication status event while making the version ineligible for future activation. It does not directly mutate Automation runtime state; runtime disablement and its reason mapping remain Automation-owned, and `REVOKED_DESIGN` alone must not be inferred as a signer-policy revocation.
- Logging & Admin may inspect all design-time states, but it must not bypass them by attempting to activate a non-`PUBLISHED` plugin version.
- Game Design must expose read surfaces such as `GetPublishedPluginVersion(tenantId, pluginId, pluginVersionId)` and `ListPluginVersionStatuses(tenantId, pluginId?)`, plus a durable `PluginVersionStatusChanged` event family, so authoring UIs and operator tooling read one authoritative publication state model.

Rollback proof uses immutable publication identities `v1a -> v2 -> v1b`: `v1b` is a newly published `pluginVersionId` that reintroduces the desired logic, and a `SUPERSEDED` or `REVOKED_DESIGN` publication ID is never reactivated. A separate same-version runtime-reactivation proof is allowed only when the artifact remains `PUBLISHED` and is reactivated after runtime disable; that runtime transition does not alter design-time publication status or identity.

Required write path:

- `UploadPluginBundle` accepts the bundle bytes, performs archive safety checks, canonicalization, provenance-specific intake checks, manifest extraction, and indexed metadata persistence. In the current signed-only flow, provenance checks include signature verification and success moves the version to `SIGNATURE_VERIFIED`; the target unsigned path uses the existing statuses and its exact-digest, approval, and attestation evidence without adding an unsigned-specific status.
  - Deterministic ingestion or signed-intake signature failures move the version to `UPLOAD_REJECTED`.
- `PublishPluginVersion` is the explicit design-time publication step for a previously uploaded bundle version.
  - It runs design-time validation over graphs, bindings, component policy, `baseVersionId`, and `abilitySchemaDigest`.
  - Validation failures move the version to `VALIDATION_FAILED_DESIGN`.
  - Success moves the version to `PUBLISHED` and emits `PluginVersionStatusChanged`.
- `AppendPluginVersionSignatureEvidence` is the target append-only mutation for a signature-only approval on an existing `(tenantId, pluginId, pluginVersionId)`, as defined by the [Game Design Service API contract](api-contracts.md#appendpluginversionsignatureevidence-target-only). It appends to a separate Game Design-owned evidence ledger while leaving the immutable payload, stored bundle, `pluginVersionId`, `bundleDigest`, publication content, and status unchanged. It is allowed only for `SIGNATURE_VERIFIED` and `PUBLISHED`; a published append does not republish the version and emits one durable `PluginVersionSignatureEvidenceAppended` event. Target reads compose the canonical ordered complete signature set from immutable base evidence plus this ledger; the current proto/storage expose only one selected `signerKeyId` and no append RPC or ledger.
  - An exact request retry returns the stored result without another append or event. A request carrying identical existing signer evidence is an idempotent no-op. Reusing a request identity with a changed normalized digest conflicts.
  - An invalid signature, a same-signer entry with conflicting signature bytes or metadata, a digest mismatch, malformed or over-limit evidence, a resulting set with no allowlisted signer, or any revoked signer fails deterministically without mutation.
- Each target plugin write operation that supports retries must use its documented stable request identity and normalized digest. No operation may overwrite signed payload content or immutable publication content in place; callers may observe existing status and evidence while retries converge on the stored result.

### Plugin Activation Failure Matrix

Runtime/operator-facing activation outcomes must remain deterministic:

| Condition | Canonical outcome |
| --- | --- |
| Plugin version is not `PUBLISHED` (including `SUPERSEDED`) | Reject activation as ineligible historical or unpublished version |
| Signer revoked or no longer trusted | Reject activation; published history remains readable but runtime enablement is blocked |
| Signer-policy lookup unavailable | Fail closed for activation until policy can be evaluated |
| `baseVersionId` does not match the instance runtime version | Reject activation |
| `abilitySchemaDigest` does not match the instance-bound digest | Reject activation |
| Required component/capability policy blocks the plugin | Reject activation with deterministic policy error |
| Activation reconciliation/writeback fails after intent is recorded | Leave runtime state unchanged or mark reconciliation failure explicitly; do not pretend activation succeeded |

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

- `UploadPluginBundle` verifies the archive, signatures, and manifest shape, then persists indexed metadata for `town-crier-v3`.
- `PublishPluginVersion` validates that `regionTemplateId:market-square` exists in `game-v12`, that `announce-arrival` is present and safe, and that the plugin remains compatible with `sha256:9dd1b7c2...`.
- `SetPluginActiveVersion` may later activate `town-crier-v3` only for instances whose `runtimeVersionId` is `game-v12` and whose bound ability schema digest matches the same value.
- If `assetRefs[]` included an entry such as `"assetRefs": [{"assetId": "bell-sfx", "path": "assets/bell.ogg"}]`, Game Design would export that asset into the plugin-version distribution manifest, persist the manifest hash with the plugin metadata, and expose the runtime-discoverable asset only through that manifest. Runtime consumers would resolve `bell-sfx` through the published plugin metadata, not by constructing object-store paths directly.

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
2. `PublishPluginVersion` discovers that binding target `regionTemplateId:market-square` does not exist in the plugin's exact `baseVersionId`.
3. Game Design sets status to `VALIDATION_FAILED_DESIGN`, emits `PluginVersionStatusChanged`, and does not create or mutate runtime registry state.
4. Any later `SetPluginActiveVersion` attempt for that `pluginVersionId` must fail deterministically because the version is not `PUBLISHED`.

## Plugin Lifecycle & Rollback

Plugins follow a lifecycle similar to script patches but scoped to `<tenantId, gameInstanceId, pluginId>` so a tenant can run multiple game instances with different plugin selections safely.

The lifecycle rules in this section are **target-state**. The current runtime persists and exposes a narrower plugin state/version and runtime-scope projection; it does not yet persist and propagate the complete `pluginActivationEpoch`, `lifecycleRevision`, `targetLifecycleRevision`, Game Session install acknowledgement, and final-fence evidence across status, event, handoff, and recovery surfaces. The target rules below therefore define the convergence contract and current gaps, not a claim that the complete lifecycle fence is already live.

- Plugins do **not** participate in the script-patch `onLoad` lifecycle. There is no plugin-scoped `onLoad` or `onUnload` contract in the first implementation slice.
- Plugin activation therefore consists only of:
  - signature and policy verification,
  - graph validation and compatibility checks,
  - loading the new plugin version into the instance-scoped runtime registry, and
  - reconciling any plugin-owned derived scheduler state such as timers.
- Any plugin setup that would otherwise require startup code must be expressed through normal event handlers (`onSpawn`, `onEnterRegion`, `onInterval`, custom events) or explicit operator/admin workflows. Implementations must not invent an implicit plugin initialization hook.

Design-time publication and runtime activation are separate:

- Publication in the current Game Design implementation means the plugin bundle is immutable, signed, validated, and available for activation; the ADR 0111 target unsigned-provenance path still requires exact package evidence and explicit approval/attestation.
- Activation in Automation & Scripting means a `PUBLISHED` plugin version has been selected for one `(tenantId, gameInstanceId, pluginId)` and admitted into the runtime registry.
- A plugin version that is `PUBLISHED` in Game Design may still be `DISABLED` or never activated for any instance.
- Game Design publication visibility and Automation runtime state must remain separate read surfaces. Operator tooling should read immutable publication metadata (for example `GetPublishedPluginVersion`) alongside runtime activation state (`GetPluginStatus`) rather than relying on one synthetic plugin-state enum to encode both concerns.

- Each plugin version is identified by `pluginVersionId`. A registry in the Automation & Scripting Service tracks, per `<tenantId, gameInstanceId, pluginId>`:
  - `activeVersionId` – the plugin version currently enabled.
  - `pendingVersionId` – a plugin version being loaded or validated.
  - `pluginState` – canonical runtime state: `ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, or `FAILED`.
  - `pluginActivationEpoch` – the monotonic current activation fence for the selected version.
  - `lifecycleRevision` – the monotonic owner transition cursor; it advances once for every committed lifecycle state transition, including same-epoch `DRAINING`.
  - `targetLifecycleRevision` – the reserved `current lifecycleRevision + 1` for a pending state-changing transition; it is carried through the Game Session install/ack and completion CAS and is not current until the owner commits.
- Enabling a plugin reports `pluginState=ENABLED` for a `<tenantId, gameInstanceId, pluginId, pluginVersionId>` only after the idempotent Game Session install and durable acknowledgement carry the exact target epoch/state/lifecycle revision; final execution then requires the exact current lifecycle revision. The canonical [ADR 0119 lifecycle fence](../../decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) owns the full ordering and projection rules.
- Disabling a plugin can follow two modes:
  - **Hard disable** – [ADR 0119](../../decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) defines the saga: initiation atomically persists a request-digest-bound pending transition/admission barrier with one reserved target epoch and `targetLifecycleRevision`, durably blocks new admission (including after restart), and leaves the current tuple unchanged. Game Session idempotently installs the target epoch/state/lifecycle revision; lost or failed acknowledgement leaves the barrier pending and fail closed, and an exact retry resumes that same install command. Only after durable acknowledgement does one Automation transaction commit the current epoch, lifecycle revision, and `pluginState=DISABLED` as non-executable; exact retries after completion return the stored result, while never-active and already-`DISABLED` requests are no-ops only under the ADR's completed-fence conditions. New triggers are rejected at admission (`finalStage=ADMISSION`, `finalOutcome=plugin_disabled`) and recorded in `script_event_audit`; already-admitted work proceeds only while its exact fences pass, and cleanup remains asynchronous.
  - **Disable after drain** – reserves a same-epoch `targetLifecycleRevision = current + 1` for `DRAINING` without making it current, installs that exact target through Game Session, and waits for durable acknowledgement before committing the Automation owner state/history/revision. Once `DRAINING` is committed, only already-admitted work whose winning admission/fence CAS durably committed the immediately preceding `ENABLED` revision before the durable Automation-owned `DRAINING` admission barrier was created may complete within the bounded drain policy; capture or observation alone is insufficient. At bounded completion or forced timeout, it reserves a new `targetPluginActivationEpoch = current + 1` and `targetLifecycleRevision = current + 1` for `DISABLED`, installs and durably acknowledges that exact target through Game Session, then commits the Automation owner state/history/epoch/revision. New triggers are rejected once draining begins and cleanup remains asynchronous. See [ADR 0119](../../decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) for the single canonical drain exception rather than repeating its full fence contract here.
- Updating a plugin involves setting a new `pendingVersionId`, loading and validating the new plugin graphs and bindings, reconciling plugin-owned timers and other derived scheduler state, and then atomically switching `activeVersionId` if validation succeeds. If validation or activation-state reconciliation fails, the new version is marked `FAILED`, `activeVersionId` remains unchanged, and triggers for the failed version are rejected at admission with an appropriate `finalOutcome` (for example, `version_unavailable` or `plugin_version_failed`).

Plugin triggers share the same Trigger Identity and `scriptEventId` lifecycle as regular scripts. Each handler invocation has one `script_event_audit` row with the required Trigger Identity fields (including `tenantId`, `gameInstanceId`, and for gameplay/runtime triggers `regionEpoch`) plus `pluginId` / `pluginVersionId` and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`) so operators can correlate plugin behavior with publish and enable/disable operations and still distinguish “DSL evaluated” from “accepted into tick queues”. Per-command acceptance, retry, and downstream outcomes belong in handoff child records keyed by the complete Command-Handoff Identity (source/target runtime scope plus `automationDispatchId` and `commandOrdinal`), not by dispatch ID alone and not in extra handler audit rows. The parent Trigger Identity and `outboxWorkItemId` remain correlation-only.

Certain safety decisions are **platform-wide and not overridable by tenant administrators**:

- Plugin component allowlists and any global “blocked component” flags are controlled by platform operators.
- Plugin-level quotas and budgets may be stricter than for core scripts by default; tenant administrators can lower their own plugin activity (for example, by increasing intervals or disabling plugins) but cannot raise plugin limits beyond operator-defined ceilings.

This ensures that even trusted tenant administrators cannot inadvertently weaken the global safety posture for plugins.

### Canonical Binding Model

Plugin bindings are authored as immutable design-time data, not as ad hoc instance-local toggles; current intake requires them in the signed bundle, while the ADR 0111 target unsigned provenance path still requires exact manifest, digest, validation, approval, and platform-attestation evidence before activation.

- The authoritative `bindings[]` array lives in `plugin-manifest.json`. For current signed intake, it is part of the signed bundle; for the target unsigned path, it is part of the accepted package and attested evidence.
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
- Instance-scoped enablement remains separate from binding definition: Logging & Admin chooses whether a published plugin version is active for a given game instance, but it does not rewrite the signed binding set during activation.

Handler identity and ordering:

- The schedulable handler identity for a plugin binding is `(tenantId, gameInstanceId, pluginId, pluginVersionId, bindingId)`. `pluginId` alone is never sufficient because one plugin version can declare multiple bindings for the same event.
- Trigger Identity, dedupe, audit, quota attribution, timer ownership, and drain/disable cleanup must retain `bindingId` alongside `pluginId` and `pluginVersionId` whenever the unit of work is binding-scoped.
- Runtime ordering delegates to the shared [canonical stable handler-order identity and field-wise comparator](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#canonical-stable-handler-order-identity): the total key is `(orderIndex, normalized finite handler-kind rank, canonical stable handler-order identity)`, with `SCRIPT` before `PLUGIN` by default unless an operator-controlled policy places `PLUGIN` ahead; designers cannot control that rank. For plugin handlers, that shared identity includes `(pluginId, pluginVersionId, bindingId)`; `bindingId` is the immutable manifest binding identity. Duplicate identical normalized total keys are rejected before `handlerSequence` assignment.
- For non-exclusive fan-out, Automation assigns a durable `handlerSequence` from that order and preserves it through handler work, command handoff, and final application. Plugin execution priority and worker timing do not override semantic handler order.
- At most one binding in the complete resolved handler set for the full applicable [Trigger Identity scope](../../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) may have `requiresExclusiveEvent=true`. For instance-bound events, that scope includes `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, concrete target identity, `eventType`, and `eventSchemaVersion` as applicable. Tenant-readiness `onLoad` uses the explicit pre-instance exception: it is tenant-scoped and omits instance, playable-state, region, and entity fields. An authorized exclusive binding is selected before fan-out and becomes the sole handler; no core-script or plugin sibling runs before or after it, and failure does not cause sibling fallback.
- Game Design rejects publish-time-known multiple-exclusive or unauthorized plugin claims against the exact `baseVersionId`. Automation & Scripting re-checks the complete resolved base-script plus active-plugin set during instance activation because active selections and bindings from different declared scopes may converge on the same concrete target.
- Plugin exclusivity requires an explicit operator grant and audit evidence bound to the exact `pluginVersionId`, `bindingId`, target policy scope, and granting actor. Manifest intent alone grants no exclusivity. Automation must revalidate the current exclusivity grant both when activating a plugin and when resolving handlers; it persists the grant version and canonical grant digest with the activation/resolution evidence. Missing, stale, revoked, narrowed, or contradictory grant evidence fails closed at that boundary.

Typed selector contracts:

- `GLOBAL` uses `{}` and is valid only for event types whose event registry marks global bindings as legal.
- `REGION` uses `{"regionTemplateId": "regionTemplateId:<stable-id>"}` and resolves against World Management region templates for the exact `baseVersionId`.
- `ENTITY_TEMPLATE` uses `{"entityTemplateId": "entityTemplateId:<stable-id>"}` and resolves against Entity Management entity templates for the exact `baseVersionId`.
- `COMMAND_ALIAS` uses `{"commandAlias": "<normalized-command-alias>"}`. In the initial slice this scope is valid only for aliases backed by the canonical built-in command registry; authored command namespaces are not yet part of the plugin-binding contract. Game Design must normalize command aliases using the same built-in command registry rules used by the runtime command parser and must reject aliases that collide with reserved commands or another binding in the same target scope.
- Future `targetScopeType` values must define their selector object, owner service, normalization rules, and exact validation API before they can appear in an accepted package manifest.

Validation responsibilities:

- Game Design validates that all declared bindings are structurally valid and resolvable against the targeted `baseVersionId`.
- Game Design validates that `entrypointGraphId` references a declared graph, `orderIndex` is in the bounded platform range, `requiresExclusiveEvent` has the required operator policy grant, and the typed `targetSelector` can be resolved by the owner service under the exact `baseVersionId`.
- Automation & Scripting consumes the validated binding set during activation and registry load; it must not invent additional bindings or infer missing targets from runtime state. The current activation path now re-checks built-in `COMMAND_ALIAS` bindings against Game Session's authoritative built-in command registry and rejects instance-scoped alias/exclusive-binding conflicts against the currently pinned script patch plus already-enabled plugins before mutating runtime state. Both activation and handler resolution must revalidate current exclusivity-grant evidence bound to `pluginVersionId`, `bindingId`, target policy scope, and granting actor, including its persisted grant version and canonical digest; absent, stale, revoked, narrowed, or contradictory evidence rejects the boundary.

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
  - In report-only mode, `policyViolations[].decision` must be `REPORT_ONLY`, and `finalOutcome` must remain the actual stage-qualified pipeline result (`handoff_accepted`, `completed_no_commands`, `sandbox_error`, `infrastructure_error`, and so on). `finalOutcome=plugin_component_blocked` is invalid while report-only mode is active.
  - Dashboards and alerts use these signals to show which plugins would be blocked if enforcement were enabled.
- **Enforcing phase** – once violations are understood and unacceptable plugins have been migrated or disabled:
  - Enforcement is enabled for the policy version; subsequent violations must set `policyViolations[].decision=BLOCKED` and cause triggers to be rejected at admission with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` that identifies the blocked component/policy decision.
  - Operators continue to monitor `automation_plugin_policy_violations_total` to detect regressions.

Example:

- In report-only mode, a plugin trigger that references a newly discouraged `world.admin.teleport` component may still execute and, when every required dispatch is accepted, finish with `finalOutcome=handoff_accepted`, while `policyViolations[]` records that component with `decision=REPORT_ONLY` and `automation_plugin_policy_violations_total` increments for the same component and policy version.
- In enforcing mode for that same policy version, the same trigger must stop at admission with `finalStage=ADMISSION`, `finalOutcome=plugin_component_blocked`, and a `finalReason` that identifies the blocked `world.admin.teleport` component or policy decision. `policyViolations[]` then records `decision=BLOCKED`, and operators should see the same component reflected in `automation_plugin_policy_violations_total`.

Policy configs should be versioned so operators can roll back to a previous allowlist if enforcement causes unexpected disruption. Report-only and enforcing behavior are configuration choices on the policy version and must be applied consistently across environments as part of the normal deployment pipeline.

Operationally, Logging & Admin acts as the operator-facing orchestration and audit surface for plugin lifecycle management. The authoritative runtime control plane remains the Automation & Scripting APIs that own `activeVersionId`, `pendingVersionId`, `pluginState`, `pluginActivationEpoch`, and `lifecycleRevision`; a pending transition also owns its `targetLifecycleRevision`. Logging & Admin coordinates those APIs rather than owning a competing runtime registry contract. The [Scripting Control Plane API](../../system-architecture-scripting-control-plane-api.md) and [ADR 0119](../../decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) own the exact install/acknowledgement and final-fence rules.

The lifecycle operation and serialization rules that follow are **target-state**. The current `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin` surfaces are present, but their current persistence and event/readback path does not yet carry the complete epoch/revision reservation, idempotent Game Session acknowledgement, and completion-CAS evidence; callers must not infer those guarantees from the current state/version response alone.

Automation serializes lifecycle mutations in one pending-transition slot for `(tenantId, gameInstanceId, pluginId)`. It captures the current `(pluginVersionId, pluginActivationEpoch, pluginState, lifecycleRevision)` tuple and reserves `targetLifecycleRevision = current + 1` for every state-changing transition, with an epoch-advancing target only when the canonical lifecycle rules require it; an exact request resumes, while a different activation, switch, drain, disable, revocation, reactivation, or policy mutation fails closed as `transition_in_progress`. Completion must CAS the unchanged tuple, pending request identity, target epoch when applicable, and target lifecycle revision after the idempotent Game Session install/acknowledgement. Security, component, and signer-policy fences remain independent and may fail closed immediately. See [ADR 0119](../../decisions/adr-0119-epoch-fenced-per-instance-plugin-activation.md) rather than duplicating its lifecycle saga here.

To roll back a misbehaving plugin, operators must publish a new trusted `pluginVersionId` that reintroduces the desired logic, then promote that newly published version to `activeVersionId` for the affected `<tenantId, gameInstanceId, pluginId>` via Logging & Admin. Historical `SUPERSEDED` versions remain immutable audit records and are not reactivated. Promoting the replacement does not reopen the admission barrier: Automation & Scripting must keep external, scheduler, and timer trigger admission paused while replacement ownership is created or confirmed, displaced ownership is retired or tombstoned, and durable timers are reconciled. Only after those ownership and timer-reconciliation steps complete successfully may the service resume admitting triggers for the restored logic version; queued-work cancellation and purge remain bounded asynchronous cleanup and are not an admission gate while exact pin/runtime fences remain authoritative. Continue enforcing quotas, budgets, and sandbox limits as described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.

Plugin rollback/disable/revocation flows must also invoke Automation's `CancelPendingWorkItemsForPluginVersion` with the exact `<tenantId, gameInstanceId, pluginId, pluginVersionId, pluginActivationEpoch, scriptPatchVersion, scriptPinEpoch>` scope, a nonempty unique `workItemIds[]` batch of at most 100 parent IDs, optional `regionId`, and a digest-bound `controlPlaneRequestId` before or alongside queue purges. Selector validation rejects over-limit, duplicate, blank, or invalid IDs before candidate reads or mutation; callers repeat bounded batches with new request IDs, while an exact retry returns the same stored batch results. IDs may cover any binding under the exact scope; each row retains its `bindingId` and captured `lifecycleRevision` as provenance and final-fence evidence, but callers cannot select either value. Each parent returns one deterministic result: `not_found_or_not_owned`/`rejected` for precondition failures; `recovery_in_progress` when any parent/child remains active or ambiguous (including `HANDOFF_IN_FLIGHT`); `canceled` only when this request changed at least one eligible row and every applicable child is terminal; and `already_terminal` only when no mutation was needed and all applicable children were terminal. `canceledCount` counts only parent results with outcome `canceled`; bounded child-state counts/reasons may accompany results. Exact plugin provenance, `(pluginActivationEpoch, lifecycleRevision)`, and runtime tuple fences reject stale queued or in-flight work before handoff or final effects, so cancellation and purge are not the correctness barrier; Game Session's final fence remains authoritative.

Rollback/disable/revocation flows must also reconcile durable plugin-owned timers:

- The timer-reconciliation operation identity includes `<tenantId, gameInstanceId, playableStateScope, targetScopeType, targetScopeId, pluginId, displacedPluginVersionId, replacementPluginVersionId?, bindingId, scheduleDefinitionId>` plus the same `controlPlaneRequestId` used by the surrounding control-plane operation. Each schedule is a separate durable child reconciliation identified by that full tuple; one parent control-plane request must not collapse distinct schedules into one child result. Each owner row retains its own `pluginVersionId`; the operation-level displaced and optional replacement owner versions must not be inferred from whichever row happens to be read first.
- For rollback or repin to a replacement `pluginVersionId`, any timer or interval owned by the displaced version must follow the scheduler continuity contract: on the default, reset, or incompatible path, reconciliation fences and tombstones the displaced owner before creating or confirming the replacement owner entry, as one atomic durable result or a resumable idempotent operation; a valid explicit typed-compatible continuity transition instead rewrites the existing stable row in place with the target ownership and pin metadata and creates no replacement row. The operation must persist and reuse the same `controlPlaneRequestId` across the applicable ownership update and displaced-row retirement, while each schedule child retains its own `scheduleDefinitionId` and full reconciliation identity; it must not generate a new request identity per timer or phase. No normal scheduling or trigger admission resumes for the plugin until ownership and durable timer reconciliation complete; queued-work cancellation remains bounded asynchronous cleanup and is not a resumption gate because exact pin/runtime fences remain authoritative.
- For disablement or revocation without a replacement version, the control plane must atomically fence admission and tombstone the displaced timer owner without creating a replacement entry, as one durable result or a resumable idempotent operation. It must persist and reuse the same `controlPlaneRequestId` across fencing and tombstoning, while each schedule child remains keyed by `<tenantId, gameInstanceId, playableStateScope, targetScopeType, targetScopeId, pluginId, displacedPluginVersionId, replacementPluginVersionId=null, bindingId, scheduleDefinitionId, controlPlaneRequestId>`; no normal scheduling or trigger admission resumes for the disabled or revoked plugin until durable timer tombstoning/reconciliation completes. Queued-work cancellation must be launched and tracked as bounded asynchronous cleanup under the same surrounding operation and remains non-gating because the exact plugin/runtime fence remains authoritative.
- Canceling queued work items alone is insufficient; otherwise an old plugin version could continue minting new timer-driven triggers after disablement or rollback.
- If a newer plugin version preserves the same schedule, carry-forward is allowed only when both the old and new definitions explicitly declare continuity for the same stable logical key and typed compatibility holds for interval kind/cadence, trigger conditions, handler identity, target and binding, playable scope, dry-run namespace, and runtime ownership/fences. If either the source or target continuity declaration is absent or false, reconciliation uses the reset/tombstone path. On that reset/incompatible path, reconciliation must fence and tombstone the old owner before creating or confirming the new `pluginVersionId` entry; on a valid continuity path, it rewrites the existing stable row in place with the target ownership and pin metadata and creates no replacement row. Both paths must explicitly rewrite ownership without reusing old trigger claims. `scheduleSemanticsHash` remains diagnostic evidence only: equality does not grant continuity and inequality does not deny an otherwise valid declaration.

### Runtime Playable-State Scope and Schedule Identity

The admission-pointer `stateScope` is the canonical realm policy. Game Design maps it to the runtime `playableStateScope` value without inventing a second authority: `SHARED` maps to `PLAYABLE_STATE_SCOPE_SHARED`, and `ISOLATED` maps to `PLAYABLE_STATE_SCOPE_ISOLATED`. The resolved value is carried on runtime bindings and schedule definitions so shared and isolated state cannot collide.

`scheduleDefinitionId` is one component of the stable logical key and may be reused across patch or plugin versions only through the explicit continuity contract above. Game Design must persist and publish the canonical normalized schedule-semantics digest as diagnostic `scheduleSemanticsHash`; equality does not grant continuity and inequality does not deny an otherwise valid declaration. A changed logical key or failed typed compatibility check for interval kind/cadence, trigger conditions, target binding, `playableStateScope`, handler identity, dry-run namespace, or runtime ownership/fences uses the reset/tombstone path rather than carrying the schedule row forward; hash comparison must not substitute for those checks.

The normative control-plane API shapes and required events for plugin management are defined in `design/architecture/system-architecture-scripting-control-plane-api.md` (for example `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin`).
For operator verification during rollback, disablement, or signer revocation, use the same control-plane read surfaces that gate scripting convergence for the affected runtime scope, including plugin-state reads from `design/architecture/system-architecture-scripting-control-plane-api.md` and the scripting drain/convergence workflow reads delegated from `design/architecture/system-architecture-scripting-control-plane-operations.md`.

## Monitoring & Debugging

Live plugin executions (`isDryRun=false`) participate in the same observability pipeline as live core scripts and use shared identifiers and live metric families. Preview/test executions remain isolated in the preview or test result/audit surface and use the corresponding test-only metric families; they do not increment the live families below:

- Each live plugin trigger is recorded in `script_event_audit` with the required Trigger Identity fields (including `tenantId`, `gameInstanceId`, and for gameplay/runtime triggers `regionEpoch`), plus `pluginId` / `pluginVersionId` and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`).
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
- [User Journeys – Extensibility & External Tools](../../../product/user-journeys/creators.md#8-extensibility--external-tools)
- [System Architecture – Scripting & Automation](../../system-architecture-scripting.md)
- [Asset Storage Setup](asset-storage.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md)
