# Game Templates and Configuration Tools

This document expands on how the Game Design Service provides reusable templates
for new games. Templates bundle world data, scripts and default settings so that
creators can quickly spin up new projects without starting from scratch.

## Template Contents

- **World Layout** – predefined regions and rooms loaded from the World
  Management Service.
- **Starter Items and NPCs** – basic entity definitions for a new game.
- **Default Rulesets** – gameplay rules and runtime flags stored with the
  template.
- **Admin Accounts** – initial administrators configured at template creation.

## Implementation Status

- The launch plugin-selection presence modes remain target-only and unimplemented: a presence-capable selection wrapper/mode, fresh omission or explicit empty resolving to the same immutable empty selection, rollback omission reusing a named target, rollback explicit empty, and explicit non-empty selection are not yet represented in proto/generated clients, launch-descriptor persistence/response, the request digest, service logic, or focused proof. Launch-resolution APIs are live, but the current implementation does not yet persist, expose, or prove the complete `enabledPluginVersions[]` set for deterministic retry and rollback. The detailed target contract remains below.

## Starter Experience Profiles

Game Design provides curated starter experience profiles so a creator can begin with a coherent playable ruleset without hand-authoring every stat, condition, action, floor-disposition, observation/targeting/target-selection-policy/default-path binding, feedback declaration, and ordinary script. Examples may include a classic text-MUD baseline, a solo-RPG baseline, and a minimal sandbox baseline.

A game selects one optional base profile and zero or more optional extension packs while building a Draft version. Game Design materializes their content into that Draft version as ordinary versioned DML and scripts; profiles never remain as live runtime inheritance.

- Imported definitions are game-owned after application and may be edited, replaced, or removed. A creator may select no profile.
- A base profile and optional packs compose in explicitly declared order. Duplicate definition keys are rejected unless the later pack records an explicit override of the earlier definition; implicit last-writer-wins merging is prohibited.
- Game Design records every selected pack's identity, revision, hash, application order, explicit overrides, and materialization schema/compiler version as Draft provenance. It also retains, or makes permanently reconstructible, the exact resolved applied baseline, stable namespaced source identities, source-to-game authored-identifier mappings, application and upgrade commit lineage, and explicit locally deleted, detached, and retained state. Publishing freezes only the resulting single game version and release bundle.
- Profiles are not runtime settings and provide no hidden fallback behavior. Removing an imported definition removes it from that game's published design; runtime services must not substitute a platform default.
- A profile may suggest ordinary tenant/game setting values, such as the default bounded-resource capacity-change policy. Accepted suggestions are written separately through the ordinary typed settings authority, are not inherited from the profile at runtime, and may be changed or removed independently of imported content. Applying or upgrading a profile never silently overwrites a setting.
- A profile may materialize ordinary scripts. Linked plugins remain separate immutable platform-attested release-tuple dependencies: profile application or upgrade does not rewrite a plugin bundle or make a plugin version compatible with a different base game version.

This model keeps templates convenient while retaining the single-base-version and immutable-release invariants below. The profile authority and upgrade boundary are defined by [ADR 0124](../../decisions/adr-0124-materialized-starter-profiles-with-conservative-draft-upgrades.md); this document owns only template-local references, defaults, and validation consequences.

### Profile Upgrade Planning

A newer profile revision never changes an existing game automatically. A creator may explicitly request an upgrade while editing a Draft. The planner compares:

- `O` – the exact old resolved base-and-pack composite recorded as the current application baseline;
- `L` – the current game-owned Draft; and
- `N` – the exact newly selected resolved composite.

The automatic plan is object-level and conservative. An object canonically unchanged from `O` may be replaced with its `N` representation or deleted when `N` removed it. A newly introduced object may be added after identity, collision, schema, and reference validation. Locally changed or deleted objects, broken references, unsafe deletions, ambiguous overrides, and semantic replacements, splits, merges, renames, or re-scopes require explicit creator resolution and any required durable identity mapping. The planner does not perform a generic JSON merge or promise automatic field-level merge.

The creator previews and resolves the complete plan before Game Design applies it as one normal Draft commit guarded by current Draft aggregate or scope epochs. A concurrent edit invalidates the preview and requires replanning. The resulting Draft follows the ordinary reconciliation, validation, digest, and publication workflow. Runtime rollout of the published version follows the ordinary game-version migration and cutover contracts; there is no profile-specific runtime path.

After a successful upgrade, the exact profile baseline advances to `N`, while retained local differences, explicit deletions, detachments, mappings, and resolution outcomes remain recorded for the next upgrade. This makes repeated upgrades deterministic without turning the profile into an authoring or runtime inheritance layer.

`GameTemplateDto` includes `id`, `tenantId`, `name`, an optional `description`,
the raw `config` JSON and a `createdAt` timestamp. The `id` is assigned by the
database when the template is saved. The `config` field uses a structured
schema describing world layout, starter items, default rulesets, and admin
accounts.

The `config` payload does not embed authoritative copies of world, entity, or script definitions. Instead it carries:

- References to world templates (regions, rooms) using stable identifiers owned by the World Management Service and scoped by `(tenantId, versionId)`.
- References to starter items, NPCs, and equipment using stable identifiers owned by the Entity Management Service and scoped by `(tenantId, versionId)`.
- References to rulesets and scripts via identifiers defined by the Automation & Scripting Service.

Creator-facing launch-default note:

- A template-pinned `scriptPatchVersion` is an explicit default chosen at authoring time.
- Caller overrides may fill unset launch fields, but they must not silently replace a template-pinned script patch.
- If a creator wants a different default patch, the template itself must be updated and republished rather than relying on arbitrary per-launch override behavior.

Canonical schemas, identifiers, and versioned template rows remain in the owning domain services; `GameTemplateDto.config` is a configuration and wiring layer that composes these existing templates for bootstrapping new games.

### Normalized Reference Storage

Game templates participate in version retirement, auditing, and bulk migration workflows. Because `GameTemplateDto.config` is JSON, the system must not rely on ad hoc JSON parsing to enforce invariants like “do not retire a version that is still referenced” or to perform controlled rewrites of references.

The Game Design Service therefore stores normalized reference rows alongside the JSON config, derived and validated on every create/update:

- `game_template_version_ref` keyed by `(tenantId, gameTemplateId, versionId)` for the base design bundle referenced by the template.
- `game_template_world_ref` keyed by `(tenantId, gameTemplateId, versionId, regionTemplateId/roomTemplateId/...)` for any explicit world references present in the config.
- `game_template_entity_ref` keyed by `(tenantId, gameTemplateId, versionId, entityTemplateId/lootTableId/...)` for starter items/NPCs and related entity wiring.
- `game_template_script_ref` keyed by `(tenantId, gameTemplateId, versionId, scriptId/...)` for script bindings where templates need to pin or validate script identifiers.
- `game_template_script_patch_ref` keyed by `(tenantId, gameTemplateId, baseVersionId, scriptPatchVersion)` when a template pins a default `scriptPatchVersion` for a base version.

Administrative tooling and lifecycle checks (retirement eligibility, “list templates referencing version”, bulk migrations) operate on these normalized tables. The JSON config remains the user-facing payload and can be reconstructed or validated against normalized rows, but it is not the only queryable representation of dependencies.

### Single Base-Version Invariant

Launchable game templates must resolve to exactly one base design bundle version.

The runtime architecture owns the complete resolved tuple and admission predicate. Game Design owns the normalized template-reference invariant that supplies the tuple's base-version input; this section does not define a second runtime release-selection policy.

- `game_template_version_ref` is not advisory metadata; it identifies the single canonical `versionId` used to resolve the launch descriptor for that template.
- Every normalized world/entity/script reference row for the template must use that same `versionId`.
- Mixed-version template composition is not allowed for launchable templates. A template update that would produce reference rows spanning multiple base `versionId` values must fail validation rather than relying on runtime resolution heuristics.
- `game_template_script_patch_ref` is the only supported patch-level override and must reference the same `baseVersionId` as the template’s canonical `game_template_version_ref`.
- If future workflows need cross-version migration planning, they must represent that as an explicit control-plane migration artifact, not as a launchable template with mixed-version dependencies.

Normalized reference invariants:

- The normalized reference tables are authoritative for dependency queries (retirement eligibility checks, “list templates referencing version”, bulk migration planning). The system must not rely on best-effort parsing of arbitrary JSON to determine dependencies.
- Create/update operations must update `game_templates.config` and all corresponding `game_template_*_ref` rows in the same database transaction. Partial updates are not allowed.
- If reference derivation fails (for example malformed config), the template write must fail; the service must not persist a config that cannot be represented in normalized reference rows.
- If derivation yields more than one base `versionId`, the template write must fail with a clear validation error explaining that launchable templates must resolve to one version bundle.
- Introducing normalized reference tables requires a one-time backfill migration/job for existing templates. Backfill must validate consistency and mark templates `INVALID` if dependencies cannot be derived or resolved.

### Backfill, Validation, and Runtime Usage

Normalized reference storage is only safe if it is operationally enforced:

- **Cutover phases** – normalized-reference rollout is stateful and must use explicit phases:
  - `BACKFILLING` – reference rows are being derived and validated; templates may exist that are not yet safe for runtime dependency checks.
  - `VALIDATED` – backfill completed and consistency checks passed for all templates in scope.
  - `ENFORCED` – runtime and control-plane reads for dependency/retirement checks use normalized tables as the sole source of truth.
  - Instance creation must be blocked for tenants still in `BACKFILLING`.
  - Once in `ENFORCED`, dependency checks must not fall back to best-effort JSON parsing.
  - Phase scope is explicit and persisted per `tenantId`. Tooling must not infer phase from partial row counts.
  - Phase must be stored in a durable control-plane row (for example `template_reference_phase`) with a monotonic epoch for CAS-safe transitions (`BACKFILLING -> VALIDATED -> ENFORCED`).
  - A read API such as `GetTemplateReferencePhase(tenantId)` must expose the persisted phase to Game Session and retirement tooling.

- **Backfill job/migration** – when normalized reference tables are introduced (or when their derivation rules change), the Game Design Service must run a backfill process that:
  - Re-derives all `game_template_*_ref` rows from existing `game_templates.config`.
  - Validates that every referenced `(tenantId, versionId, templateId)` exists in the owning domain service and that the referenced `versionId` is not Retired.
  - Marks the template as `INVALID` (and blocks instance creation) if references cannot be derived, cannot be resolved, or violate lifecycle constraints.
- **Strict create/update enforcement** – create/update of a template must be rejected if normalized references cannot be derived and written in the same transaction as the JSON config.
- **Single-version launch enforcement** – instance creation and `ResolveLaunchDescriptor` must reject any template not marked `VALID` with exactly one canonical base `versionId`.
- **Instance creation enforcement** – the Game Session Service (or the instance-creation orchestrator) must validate template dependencies using normalized tables before creating any `gameInstanceId` rows:
  - Fail fast if any referenced version is Retired, missing, or out of sync with its domain templates.
  - If the template pins a `scriptPatchVersion`, fail fast unless Automation & Scripting reports that patch is `READY` for the tenant.
  - Fail fast if `GetTemplateReferencePhase` is not `ENFORCED` for the target scope.
  - Defer release-attestation semantics to [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules); locally, Game Design must reject a template launch when the resolved version has no supported attestation identity or the descriptor cannot bind to that version.
  - If the launch path depends on cross-version durable-state remaps, fail fast unless an approved `remapSetId` exists for the source/target version pair and all required owning domains attest it as usable.
  - Game Design now persists this control-plane state explicitly and returns the frozen `remapSetId` on the resolved launch descriptor rather than requiring downstream services to infer or rediscover remap identity.

> **Note**

Templates are **versioned** like any other design asset. Publishing a version does **not** copy a separate design database into the domain services. Instead:

- The Game Design Service persists `game_templates` rows and their revision history as design-time artifacts.
- World Management, Entity Management, and related domain services already own the authoritative versioned templates keyed by `(tenantId, versionId)`; publish finalizes those Draft templates via the `version_id` workflow described in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md).
- `GameTemplateDto.config` composes existing domain templates by reference and is validated at publish time; it never becomes a competing source of truth for world or entity graphs.

Ownership can be summarized as:

- **Game Design Service** – owns `game_templates` and all version/control metadata for templates.
- **World Management / Entity Management / Automation & Scripting** – own world, entity, and script templates and their identifiers; game templates only reference these records via stable IDs.

### Runtime Defaults

Game templates may optionally carry default runtime configuration alongside their structural wiring:

- `GameTemplateDto.config` can include optional fields such as a default `scriptPatchVersion` or initial feature-flag presets that the Game Session Service uses when creating new `gameInstanceId` values from the template.
- Templates must not implicitly promise survival of instance-scoped world state across replacement-instance upgrades. Any persistent carry-forward behavior must be defined by the runtime-state upgrade contract in `system-architecture-versioning-runtime.md`.
- When these defaults are present, instance-creation flows must apply them explicitly; when they are absent, callers may provide the desired `scriptPatchVersion` and runtime flags at creation time. If neither the template nor the caller selects a script patch, resolution uses explicit null/semantic `UNPINNED` and creates no script work. Templates must not implicitly select “latest READY patch” or other moving targets without operator input.
- If a template pins a default `scriptPatchVersion`, instance creation must validate that Automation & Scripting has marked that patch `READY` for the tenant and that the patch targets the template's canonical `baseVersionId` before pinning it for a running instance; otherwise instance creation fails with a clear error and no instance rows are created.
- Caller-supplied runtime overrides are only allowed to fill fields the template leaves unset. If the template already supplies a runtime default, any caller-supplied value for that field is a deterministic launch-resolution failure instead of being merged heuristically.

Template defaults are design-time inputs only. A resolved `scriptPatchVersion` is an exact candidate for initial instance creation; it does not allocate or imply the Game Session-owned `scriptPinEpoch`, and a template update cannot rewrite the pin or rollout history of an existing instance. A later script-patch rollout or rollback is an explicit Game Session control-plane operation after Automation tenant readiness, with exact tuple validation and no moving-target `latest READY` lookup. A full-version (`runtime_version`) rollback instead follows the [replacement-instance cutover](../../system-architecture-versioning-runtime.md#replacement-instance-upgrade-contract) predicates, including release attestation, version state, compatibility/remap, lifecycle fencing, and admission-pointer route swap. Linked plugin versions in a launch descriptor remain immutable requested `pluginId`/`pluginVersionId` selections for launch/rollback, not proof of runtime activation; Automation owns the separate post-launch activation step and validates each selected exact `(pluginId, pluginVersionId, bindingId)` tuple against its runtime-state fence.

### Resolved Launch Descriptor

Template-driven instance creation must materialize one immutable resolved launch descriptor before any `gameInstanceId` rows are created. The complete tuple and admission contract is canonical in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules); this document records the Game Design consequence that template references and defaults are resolved once, then passed through unchanged. Runtime services must not independently reinterpret `GameTemplateDto.config`, re-resolve defaults, or fetch moving-target control-plane state during launch.

Canonical minimum fields:

- `launchDescriptorId`
- `controlPlaneRequestId`
- `tenantId`
- `gameTemplateId`
- resolved `versionId`
- resolved `scriptPatchVersion` (or explicit null when none is pinned)
- **Target-state:** normalized `pluginSelectionMode` plus complete deterministically ordered `enabledPluginVersions[] { pluginId, pluginVersionId }`, persisted in the Game Design-owned launch-descriptor record as an immutable requested plugin selection bound to immutable publication and compatibility evidence for each selected version, reused unchanged by same-identity retries; its presence is not proof of Automation runtime activation
- resolved runtime feature flags/defaults
- `generationConfigRevision` taken from the target version’s `published_release_bundle`
- `versionStateEpoch` used for CAS-safe activation checks
- any approved `remapSetId` required by the launch path
- opaque `publishedReleaseBundleRef`, persisted as an immutable descriptor input and reused unchanged by same-identity retries and rollback

Resolution invariants:

- `resolved versionId` is derived from the template’s single canonical `game_template_version_ref`; it is never inferred by choosing one of several referenced versions at launch time.
- `resolved scriptPatchVersion`, when present, must be validated against that same base `versionId`.
- Runtime services must treat any mixed-version template as invalid configuration and fail before any instance rows are created.

Ownership and usage rules:

- Game Design Service owns deterministic resolution of template metadata and normalized references into this descriptor, or exposes a read API that lets the instance-creation orchestrator do so deterministically from Game Design-owned state.
- Retries for the same launch attempt, keyed by the same `controlPlaneRequestId` and the same input fields, must reuse the same descriptor values and must not re-resolve to a newer attestation, patch, or runtime default.
- A fresh launch attempt with a new `controlPlaneRequestId` may resolve to newer valid state if the underlying published metadata has advanced.
- World creation, Game Session admission, and script-patch pinning consume this descriptor as input; they must not fetch "latest READY patch" or re-parse template JSON mid-flight.
- If descriptor resolution fails because a dependency is missing, the selected script patch is not tenant-`READY`, required immutable publication/compatibility evidence is absent, the release is not attested, or the dependency is not enforceable under `GetTemplateReferencePhase`, the launch fails before any instance rows are created. Plugin runtime readiness and current signer, component, and capability policy remain Automation activation/resume gates rather than launch-descriptor predicates.

### Launch Orchestration Ownership

The first implementation slice must use one control-plane launch orchestrator. The canonical owner is the Game Session instance-creation workflow consuming Game Design control-plane APIs.

Required ordering:

1. Read `GetTemplateReferencePhase(tenantId)` and fail fast unless the phase is `ENFORCED`.
2. Call `ResolveLaunchDescriptor(...)` in Game Design and receive immutable resolved values.
3. Read `GetPublishedReleaseBundle(tenantId, versionId)` and verify the canonical release-attestation predicate in [Versioning & Runtime Configuration](../../system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules) before any instance row is created. Game Design's local consequence is to validate the returned tenant/version scope and preserve the resolved descriptor's opaque release-attestation identity; it must not reconstruct release truth from template JSON or storage paths.
4. Only after steps 1-3 succeed may the orchestrator create any persistent `gameInstanceId` row or request World Management to create `PREPARING` instance state.
5. World creation then executes using only the resolved descriptor values and must not re-resolve template JSON, patch defaults, or release metadata mid-flight.

No service may create persistent instance rows before this preflight sequence completes successfully. Partial provisioning before attestation/reference validation is not an allowed first-slice behavior.

Required deterministic failure vocabulary for the first implementation slice:

- `TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED` when `GetTemplateReferencePhase` is not `ENFORCED`.
- `INVALID_TEMPLATE_CONFIGURATION` when normalized references do not converge to one canonical base `versionId` or otherwise fail launch validation.
- `SCRIPT_PATCH_OVERRIDE_CONFLICT` when a caller tries to override a template-pinned `scriptPatchVersion`.
- `SCRIPT_PATCH_NOT_READY` when the resolved or requested patch is not tenant-`READY` for the same `baseVersionId`.
- `RELEASE_BUNDLE_NOT_FOUND` when `GetPublishedReleaseBundle` is absent for the resolved version.
- `RELEASE_ATTESTATION_MISMATCH` when the attested bundle does not match the resolved descriptor values.
- `VERSION_STATE_EPOCH_STALE` when launch resolution and activation preflight no longer bind to the same frozen version-state epoch.
- `LAUNCH_REMAP_REQUIRED` when replacement-instance cutover needs an approved `remapSetId` and no unique approved remap set exists for the source/target version pair.

These are application-level launch-preflight outcomes, not transport failures. Retries for the same `controlPlaneRequestId` must return the same deterministic business result until callers intentionally start a new launch attempt with a new `controlPlaneRequestId`.

Illustrative control-plane schema:

- Request: `ResolveLaunchDescriptorRequest { tenantId, gameTemplateId, controlPlaneRequestId, requestedRuntimeFlags?, requestedScriptPatchVersion?, requestedPluginSelection?: { mode: EMPTY | EXPLICIT | REUSE_ROLLBACK_TARGET, enabledPluginVersions[]? { pluginId, pluginVersionId } }, sourceVersionId?, targetVersionId?, rollbackTargetLaunchDescriptorId? }`
- Response: `ResolveLaunchDescriptorResponse { launchDescriptorId, tenantId, gameTemplateId, versionId, scriptPatchVersion (nullable; explicit null when no patch is pinned), pluginSelectionMode, enabledPluginVersions[] { pluginId, pluginVersionId }, runtimeFlags, generationConfigRevision, versionStateEpoch, remapSetId?, publishedReleaseBundleRef }`

The exact transport schema may evolve, but every implementation must preserve the same contract shape:

- request fields identify the template, the launch attempt identity, and any caller-supplied runtime overrides that are allowed to participate in deterministic resolution;
- response fields are the immutable resolved values consumed by launch-time workflows;
- Target contract: `enabledPluginVersions[] { pluginId, pluginVersionId }` is the complete deterministically ordered immutable requested plugin selection for launch and rollback, not proof that Automation has activated runtime state. A presence-capable `requestedPluginSelection` wrapper (or equivalent `oneof`/optional-presence transport) is required; a bare repeated list cannot distinguish rollback omission from explicit empty. The normalized modes are `EMPTY` (fresh omission or explicit `[]`, and rollback explicit `[]`), `REUSE_ROLLBACK_TARGET` (rollback omission with a named target), and `EXPLICIT` (an explicit non-empty set). Fresh omission and explicit empty therefore resolve to the same immutable effective selection `enabledPluginVersions=[]`; rollback omission copies the named target's exact recorded selection, while rollback explicit empty resolves to an empty selection. No mode may infer template plugin defaults, `latest`, or another moving target. The Game Design-owned persisted launch-descriptor record binds this mode and selection to immutable publication and compatibility evidence; the response need not duplicate that evidence or transport mutable/current policy evidence. Before persistence, Game Design sorts tuples ascending by `pluginId` and then `pluginVersionId`, comparing the canonical UTF-8 byte representation of each already validated opaque identifier with no locale collation, case folding, or alternate normalization. The selection must contain at most one entry per `pluginId`; a duplicate `pluginId` is a deterministic validation failure because the scoped Automation registry has one active version per plugin. Tenant `READY` is the selected script patch's gate, not a plugin lifecycle state. Every plugin selection must prove publication as `PUBLISHED` and exact compatibility with the resolved base `versionId`, its `abilitySchemaDigest`, and the descriptor's release attestation. Signed and operator-permitted unsigned provenance evidence is defined by the [Modding Framework](./modding-framework.md#signing-and-key-lifecycle-required). These are design-time eligibility predicates, not plugin runtime readiness. The persisted mode, selection, order, and bound evidence are reused unchanged for retries with the same `controlPlaneRequestId`. A fresh rollback that reuses a prior selection must name its exact Game Design-owned `rollbackTargetLaunchDescriptorId`; Game Design verifies that descriptor's tenant, template, and `versionId` match the requested rollback target, copies its ordered plugin selection into the new rollback descriptor, and revalidates every selected plugin against the recorded target release. It does not locate the selection from `sourceVersionId`/`targetVersionId` or a mutable alias. A `SUPERSEDED` or otherwise ineligible recorded selection fails deterministically unless the new request supplies a complete explicit replacement set as described below. An exact retry of an already-bound request returns its stored result without re-resolving or revalidating against newer publication state. That descriptor replay does not bypass runtime checks: before any activation or runtime-state change, Automation rechecks current Game Design publication and rejects every state other than `PUBLISHED`, including `REVOKED_DESIGN`. After launch, Automation performs the separate activation step and validates each selected exact `(pluginId, pluginVersionId, bindingId)` tuple against its runtime-state/activation fence.
- A new launch or rollback request's `requestedPluginSelection` mode and selection are normalized before validation and included together in the request's idempotency digest. For a fresh launch without `rollbackTargetLaunchDescriptorId`, an omitted wrapper normalizes to `EMPTY` and an explicitly present empty wrapper also normalizes to `EMPTY`; both produce immutable `enabledPluginVersions=[]`. Every rollback request carries a named `rollbackTargetLaunchDescriptorId` to bind the recorded target and audit history. With that named target, an omitted wrapper normalizes to `REUSE_ROLLBACK_TARGET` and copies that descriptor's exact recorded selection; an explicitly empty wrapper normalizes to `EMPTY` and creates a new empty selection; an explicit non-empty wrapper normalizes to `EXPLICIT` and creates a new descriptor selection. The digest includes the normalized mode, the complete canonicalized selection, and the named rollback target identity when present, so presence semantics cannot be lost even when effective selections are equal. The canonical sorting, one-entry-per-`pluginId`, publication, ability-schema compatibility, and release-attestation rules above apply before computing the digest. A new request replacing an ineligible recorded plugin must therefore supply the full desired set; an exact retry returns the stored result, mode, and selection without re-resolution.
- Current implementation behavior: `GetPublishedReleaseBundle` returns the bundle `id` and `generationConfigRevision` rather than a separate reference field, so the compatibility path reconstructs `prb:<tenantId>:<versionId>:<bundleId>` from request scope and the returned `bundle.id`. It must compare that value byte-for-byte with the descriptor reference and separately compare the attested `generationConfigRevision`; this format is not the target API contract.
- Target convergence: `GetPublishedReleaseBundle` returns an owner-generated opaque `publishedReleaseBundleRef`. Consumers preserve and compare that value byte-for-byte without parsing its format, reconstructing it from identifiers, treating it as a raw UUID, or inventing another release alias.
- A request with the same `(tenantId, gameTemplateId, controlPlaneRequestId)` and the same input fields must return the same descriptor values; a request with a different `controlPlaneRequestId` is a new launch attempt and may resolve against newer valid state.
- Idempotent retries that previously produced a deterministic business failure must return the same failure code and resolved context (where applicable) rather than re-evaluating against newer publish, patch, or template state.
- If callers change any semantically relevant input field while reusing the same `controlPlaneRequestId`, the request must fail deterministically as an idempotency-key misuse rather than silently creating a second descriptor record.

Before computing the request idempotency digest, normalization rejects `EMPTY` with a non-empty list, `EXPLICIT` with an empty list, and `REUSE_ROLLBACK_TARGET` without a named rollback target or with any entries; accepted presence forms must first derive the modes and effective selections defined above consistently.

Normative examples:

The examples below intentionally use numeric `versionId` values because the current Game Design launch and published-bundle protobuf contracts expose `int64 version_id`. UUID-shaped version identifiers elsewhere in the target architecture must not be substituted into these current-contract examples until those transport fields are migrated together.

- Fresh launch from a template with no script patch pinned:

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "gameTemplateId": "gt-default",
    "controlPlaneRequestId": "ld-req-1001"
  },
  "response": {
    "launchDescriptorId": "ld-1001",
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "gameTemplateId": "gt-default",
    "versionId": 42,
    "scriptPatchVersion": null,
    "pluginSelectionMode": "EMPTY",
    "enabledPluginVersions": [],
    "runtimeFlags": {
      "pvpEnabled": false
    },
    "generationConfigRevision": "genrev-42a1",
    "versionStateEpoch": 17,
    "remapSetId": null,
    "publishedReleaseBundleRef": "opaque-release-ref-7f3c9a2b..."
  }
}
```

- Replacement-instance upgrade where durable `S2` state requires an approved remap:

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "gameTemplateId": "gt-default",
    "controlPlaneRequestId": "ld-req-2001",
    "requestedPluginSelection": {
      "mode": "EXPLICIT",
      "enabledPluginVersions": [
        {
          "pluginId": "combat-ui",
          "pluginVersionId": "plugin-version-43-1"
        }
      ]
    },
    "sourceVersionId": 42,
    "targetVersionId": 43
  },
  "response": {
    "launchDescriptorId": "ld-2001",
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "gameTemplateId": "gt-default",
    "versionId": 43,
    "scriptPatchVersion": "v43-script.1",
    "pluginSelectionMode": "EXPLICIT",
    "enabledPluginVersions": [
      {
        "pluginId": "combat-ui",
        "pluginVersionId": "plugin-version-43-1"
      }
    ],
    "runtimeFlags": {
      "pvpEnabled": false
    },
    "generationConfigRevision": "genrev-43b7",
    "versionStateEpoch": 3,
    "remapSetId": "remap-v42-v43-r1",
    "publishedReleaseBundleRef": "opaque-release-ref-a19d4e6c..."
  }
}
```

- Mixed-version template rejection:

```json
{
  "request": {
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "gameTemplateId": "gt-invalid-mixed",
    "controlPlaneRequestId": "ld-req-3001"
  },
  "error": {
    "code": "INVALID_TEMPLATE_CONFIGURATION",
    "message": "Template references multiple base versionIds (world=42, entity=43); launchable templates must resolve to one canonical version."
  }
}
```

- Preflight attestation mismatch rejection:

```json
{
  "request": {
    "controlPlaneRequestId": "ld-req-4001",
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "gameTemplateId": "gt-default"
  },
  "resolvedDescriptor": {
    "launchDescriptorId": "ld-4001",
    "versionId": 42,
    "pluginSelectionMode": "EMPTY",
    "enabledPluginVersions": [],
    "generationConfigRevision": "genrev-42a1",
    "publishedReleaseBundleRef": "opaque-release-ref-7f3c9a2b..."
  },
  "releaseBundle": {
    "id": 7,
    "tenantId": "11111111-1111-4111-8111-111111111111",
    "versionId": 42,
    "generationConfigRevision": "genrev-42b9",
    "publishedReleaseBundleRef": "opaque-release-ref-2d8e6b91..."
  },
  "error": {
    "code": "RELEASE_ATTESTATION_MISMATCH",
    "message": "Resolved launch descriptor does not match the current published release attestation for version 42."
  }
}
```

This failure occurs before any persistent `gameInstanceId` row or World `PREPARING` state is created.

Illustrative startup sequence:

1. The instance-creation orchestrator calls `GetTemplateReferencePhase(tenantId)` and fails fast unless the result is `ENFORCED`.
2. The orchestrator calls `ResolveLaunchDescriptor(...)` for the selected `gameTemplateId` and receives immutable resolved values including `versionId`, `scriptPatchVersion`, the complete `enabledPluginVersions[]` set, `generationConfigRevision`, `versionStateEpoch`, and `publishedReleaseBundleRef`.
3. The orchestrator verifies `publishedReleaseBundleRef` by reading `GetPublishedReleaseBundle(tenantId, versionId)` and confirms the attested `generationConfigRevision` matches the resolved descriptor.
4. World creation starts using only the resolved descriptor fields and persists instance rows under `(tenantId, gameInstanceId)` without re-reading mutable template defaults.
5. Game Session opens admission only after World reports successful activation for that same resolved descriptor.

### Interaction with Version Lifecycle

Game templates participate in the same version lifecycle as the domain templates they reference:

- A `GameTemplateDto` may reference only versions that are not in the `Retired` state (also referred to as “Archived” in some UIs). When saving or updating a template, the Game Design Service must validate that all referenced `(tenantId, versionId, templateId)` triples exist and that the corresponding `versionId` is still eligible to be activated.
- The `RetireVersion` workflow in Game Design or Logging & Admin Services must refuse to retire a version while any game templates still reference it. Designers must migrate those templates to a successor version (for example by creating new templates pointing at the new version’s templates) before the old version can be retired.
- Templates that reference missing or invalid templates (for example due to a failed migration) should be marked as `OUT_OF_DATE` or `INVALID` in metadata and blocked from use when creating new games until designers repair or migrate them.

## Creating Templates

Creators submit a `GameTemplateDto` via the REST API:

```bash
curl -X POST http://localhost:8080/templates \
     -H 'Content-Type: application/json' \
     -d '{"tenantId":"11111111-1111-4111-8111-111111111111","name":"Default","config":"{}"}'
```

The service validates the payload and stores it in the `game_templates` table.
Templates can then be listed per `tenantId` to help bootstrap new games.
Template names must be unique for each tenant to avoid collisions.

To list templates:

```bash
curl "http://localhost:8080/templates?tenantId=11111111-1111-4111-8111-111111111111"
```

See [openapi.yaml](../../../../services/game-design-service/src/main/resources/openapi.yaml)
for request and response schemas.

Management exists via REST and gRPC. Use `POST /templates` to create templates,
`GET /templates?tenantId=<id>` to list them, and the gRPC endpoints to create,
list, update, or delete templates.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
