# Game Authoring, Publishing, and Activation

## Current Status

The lossless source transposition is complete. This tracker consolidates Game Design authoring, settings, publication, release admission, and activation by capability; the unchanged source evidence remains the audit backstop while Spark coverage review verifies every allocation. The live implementation is strongest in control-plane publication, release attestation, launch preflight, and the first typed World authoring handoff; broader creator/editor UX and downstream ownership synchronization remain incomplete.

## Implementation Record Index

Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.

| Capability and ownership focus | Source-declared status | Source range | Evidence |
| --- | --- | --- | --- |
| [Movement and Topology Settings Vertical Slice Task List](../vertical-slices/02.12-task-list-movement-and-topology-settings-vertical-slice.md) - Settings authority, publication, and authoring ownership | implemented for pre-06 scope | 20-30, 56-64 | [source evidence](#source-02-12-task-list-movement-and-topology-settings-vertical-slice-20-30-56-64) |
| [Authored Action Definition and Execution Model Vertical Slice](../vertical-slices/02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md) - Authored definitions, publication, and release admission | partially complete; the bounded release-admitted self-targeted `APPLY_ACTION_STATE` v1 path is live, while the broader authored-action model remains in progress | 1-37, 43-49, 51-88, 90-97, 100-130 | [source evidence](#source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130) |
| [Publish and Script Patch Temporal Migration Vertical Slice](../vertical-slices/02.20.3-task-list-publish-and-script-patch-temporal-migration-vertical-slice.md) - Publish and release-attestation workflow ownership | implemented at the current boundary | 1-19 | [source evidence](#source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19) |
| [Platform Settings Model and Generated Config Docs Vertical Slice Task List](../vertical-slices/02.9-task-list-platform-settings-model-vertical-slice.md) - Platform settings model | pre-`06` complete | 1-150 | [source evidence](#source-02-9-task-list-platform-settings-model-vertical-slice-1-150) |
| [Settings Presets and Operator Baselines Vertical Slice Task List](../vertical-slices/02.9.1-task-list-settings-presets-and-operator-baselines-vertical-slice.md) - Settings presets and operator baselines | planned | 1-46 | [source evidence](#source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46) |
| [Settings Authority and Persistence Vertical Slice Task List](../vertical-slices/02.9.2-task-list-settings-authority-and-persistence-vertical-slice.md) - Settings authority and persistence | implemented | 1-61 | [source evidence](#source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61) |
| [Effective Settings Resolution and Invalidation Vertical Slice Task List](../vertical-slices/02.9.3-task-list-effective-settings-resolution-and-invalidation-vertical-slice.md) - Effective settings resolution | implemented | 1-49 | [source evidence](#source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49) |
| [Generated Settings Schema and Reference Vertical Slice Task List](../vertical-slices/02.9.4-task-list-generated-settings-schema-and-reference-vertical-slice.md) - Generated settings schema | implemented | 1-41 | [source evidence](#source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41) |
| [Game-Authored Help Storage and Layering Vertical Slice](../vertical-slices/04.6.1-task-list-game-authored-help-storage-and-layering-vertical-slice.md) - Game-authored help storage | implementation complete, pending CI and review | 1-154 | [source evidence](#source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154) |
| [Inventory and Equipment Settings Vertical Slice Task List](../vertical-slices/06.1-task-list-inventory-and-equipment-settings-vertical-slice.md) - Inventory and equipment settings | complete at the current boundary | 1-59 | [source evidence](#source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59) |
| [`08` Game Design Publishing and Runtime Activation](../vertical-slices/08-task-list-game-design-publishing-and-runtime-activation-vertical-slice.md) - Game design publication and activation scope | in progress | 1-58 | [source evidence](#source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58) |
| [Publish Digest Gating and Release Attestation Vertical Slice](../vertical-slices/08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md) - Publish digest gating and release attestation | complete for the current publish/launch/activation/repair boundary | 1-108 | [source evidence](#source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108) |
| [Published Asset Manifest and Purge Lifecycle Vertical Slice](../vertical-slices/08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md) - Published asset lifecycle | complete for the current implementation boundary | 1-67 | [source evidence](#source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67) |
| [Launch Descriptor and Activation Preflight Vertical Slice](../vertical-slices/08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md) - Launch descriptor and activation preflight | complete for the current implementation boundary | 1-79 | [source evidence](#source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79) |
| [Script Patch and Plugin Publication Boundaries Vertical Slice](../vertical-slices/08.4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice.md) - Script and plugin publication boundaries | complete at the current boundary | 1-66 | [source evidence](#source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66) |
| [World Design Mutation API Surface Vertical Slice](../vertical-slices/08.5-task-list-world-design-mutation-api-surface-vertical-slice.md) - Version-scoped authoring and publication handoff | complete | 10, 24-26, 49-51, 62 | [source evidence](#source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-10-24-26-49-51-62) |

## Canonical Design Sources

- [Settings model](../../architecture/system-architecture-settings-model.md) defines ownership, precedence, effective resolution, and generated reference contracts.
- [Runtime versioning](../../architecture/system-architecture-versioning-runtime.md) defines version-scoped definitions, immutable publication, release admission, and activation.
- [Game Design Service](../../architecture/microservices/game-design-service/README.md) owns authored definitions, version control, settings authority, releases, and activation inputs.
- [Game Design ability and action tools](../../architecture/microservices/game-design-service/ability-action-tools.md) defines authored action data, targeting, admission, and effect policy.
- [World Management Service](../../architecture/microservices/world-management-service/README.md) owns authoritative Draft/Published world templates, version-scoped mutation, and activation handoff.

## Consolidated Implementation Record

### Settings Authority and Effective Resolution

The implemented pre-`06` settings model separates service boot configuration from tenant/game behavior policy. Operator and bootstrap defaults remain service-local typed properties. Game Design is the shared persisted authority for tenant/game overrides, and `common-platform-core` merges persisted tenant overrides before game-instance overrides. Runtime services perform the final merge against their local operator defaults; there is no centralized cross-service operator-default, cap, or preset resolver and no distributed invalidation push model.

The live typed property seams are `FiremudReconnectionProperties` and `FiremudCommandHistoryProperties` in `common-data-runtime`, `FiremudCommandCapabilitiesProperties` in `common-platform-core`, `CommunicationProperties` in Game Logic, and `PresentationProperties`, `MovementProperties`, and `WorldTopologyProperties` in Game Session. Reconnect transcript/buffer policy belongs to `FiremudReconnectionProperties`; prompt emission and presentation policy belongs to `PresentationProperties`.

The persisted authority stores one JSONB `game_settings_override` row per `{tenantId, optional gameInstanceId, domain}` for `reconnection`, `communication`, `presentation`, `movement`, `worldTopology`, `commandHistory`, and `commandCapabilities`. Its bounded gRPC contract is `GetScopedSettingsOverrides`, `PutSettingsDomainOverride`, and `DeleteSettingsDomainOverride`. The old bounded file/env tenant/game override path in Game Session was removed. Current consumers include Game Session presentation, movement, topology, reconnect, command-history, and command-capability resolution plus Game Logic communication and command-capability resolution.

Game Session exposes `/actuator/settings/effective` for a persisted session or synthesized tenant/game-instance scope, including effective `commandHistory` and `commandCapabilities` results. Game Logic exposes `/actuator/settings/effective/communication`, including game-instance scope. Effective topology is returned in normalized subgroups `movementPostMoveView`, `worldTopologyScopeModel`, and `worldTopologyRegionBehavior`; region-capable topology settings normalize inconsistent `scopeModel` and `regionsEnabled` combinations to one canonical state. Movement and topology are resolved and inspectable, but later routing and communication-scope use of topology remains future work. Runtime readers use a short TTL cache with explicit per-scope refresh/evict operations.

The first end-to-end reconnection model preserves the recorded behavior contract: stale resume falls through to fresh entry, the screen buffer excludes prompts, and reconnect restore emits a fresh `LOOK` followed by one fresh prompt. `MoveCommandHandler` reads the post-move redraw policy from typed configuration rather than treating automatic post-move `LOOK` as an unconfigurable constant. The platform settings model also keeps raw Spring or transport constants out of gameplay/admin settings unless they are deliberately promoted into the typed model. It defines domain-oriented grouping and setting ownership rather than unrelated per-service blobs.

### Settings Schema and Generated Reference

Spring Boot configuration metadata generation is enabled in the application services. The checked-in outputs are [platform-settings-schema.json](../../architecture/generated/platform-settings-schema.json) and [platform-settings-reference.md](../../architecture/generated/platform-settings-reference.md), generated from surfaced Spring metadata plus `config/settings/platform-settings-publication.json`. Surfaced metadata includes stable key/path, description, default, range or enum, scope/owner, hot-reloadability, advanced flag, and an example value. The generated outputs are the settings reference source for later admin/creator tooling rather than a parallel hand-maintained catalog. The available generation/verification tasks are `./gradlew updatePlatformSettingsDocs` and `./gradlew verifyPlatformSettingsDocs`.

### Presets and Operator Baselines

The settings-preset slice is planned, not implemented. A preset is intended to be a named operator/deployment baseline that expands into ordinary effective settings before per-setting operator overrides, not a second settings system. The documented target precedence is hardcoded safe defaults, selected preset, operator bootstrap/runtime overrides, operator caps, then tenant/game overrides; the live implementation currently has only service-owned defaults plus persisted tenant/game layers. Preset family selection, explicit operator override behavior, and advanced-setting presentation still need implementation and proof. The intended experience is defaults-first and sparse override usage, with advanced knobs available without making them mandatory.

### Inventory and Equipment Authority Split

The current inventory settings surface is exactly tenant/game `commandCapabilities.inventoryEnabled`, which controls standard `INVENTORY`, room-ground, container, and equipment command availability. `INVENTORY`, `GET`, `DROP`, selector grammar, visible-item reference semantics, and room-ground transcript semantics remain one shared platform command contract, not per-game settings. No current operator-only inventory tuning field or operator cap has a demonstrated need.

Versioned game-authored DML, not settings, owns equipment slot definitions, slot-group compatibility, body-layout membership, character body-layout keys, item equipment compatibility, and stackability/fungibility. The published game version supplies that data; Entity Management persists and validates runtime bindings against release-owned data. Selector, transfer, audit, and holder-ownership rules remain canonical platform contracts. A future `inventory.*` or `equipment.*` setting is warranted only for a concrete runtime policy that is neither authored content nor a protocol/invariant.

The current creator-facing item/equipment surface is not a complete editor: the versioned DML, Game Design revision/publish control-plane substrate, and Entity Management runtime validation for slots, body layouts, compatibility, and stackability are present, while item-stat editing, equipment curves, economy-impact views, and the broader web editor remain future application work.

### Authored Help Storage and Layering

The built-in platform-owned `HELP` corpus remains code/resource-backed in Game Session for common topics including login, play, movement, inventory, equipment, containers, and communication. The bounded game-authored topic storage and layering path is implemented and merged. Authored topics carry a canonical topic ID, title, body, normalized tags/aliases, tenant and template ownership, and publication/lifecycle state. Game-authored content is owned by `{tenantId, gameTemplateId}`; `gameInstanceId` is only the runtime bridge used by Game Session to obtain the admitted template identity.

Game Design provides authenticated gRPC topic resolution and admin-gated put, list, and delete operations. Authored topics use normalized exact canonical-topic and alias/tag keys, reject canonical/alias collisions, and only published topics participate in player reads. Lookup precedence is authored canonical topic, authored alias/tag, platform canonical topic, platform alias/tag, then an explicit unknown-topic result. Game Session falls back to the platform corpus when there is no admitted template, no published authored match, or Game Design is unavailable. This keeps tenant and template isolation explicit and prevents authored content from becoming global mutable state.

Focused proof covered Game Design canonical and alias resolution, published-only reads, admin error mapping, Game Session template bridging, tenant isolation, and platform override behavior. The bounded first shape intentionally excludes rich authoring UI, fuzzy or semantic search, AI answers, localization-aware authoring, related-topic graphs, moderation/versioning beyond ordinary authored-content ownership, and dynamic command-discovery metadata integration.

### Versioned Authored Definitions and Actions

Built-in and authored commands use one provider-backed `TextCommandRegistry` and dispatcher. The active registry contains built-ins plus authored definitions admitted from the current game instance's published release artifact. Definitions are keyed by canonical `commandId` rather than requiring a `TextCommandType`; aliases are part of the definition. Aggregation rejects duplicate command IDs and duplicate aliases, including collisions with built-ins. Current authored definitions carry `TextCommandType.AUTHORED`, canonical ID, aliases, stage, prompt policy, primary action category, activity/action tags, and authored source ownership. Feature availability and stage checks occur before dispatch. The canonical `ActionAdmissionTag` catalog and ordered DML `admissionTags` actor-admission metadata remain future work and must stay distinct from the current activity/action tags.

`TextCommandParser` carries canonical authored IDs into `TextCommandPayload.AuthoredActionInvocation` and resolves authored/extension command IDs even when alias metadata is absent. The authored dispatch handler fails closed on unknown authored action IDs. `HELP` discovery and direct lookup project authored topics from the admitted registry. The obsolete configuration-backed authored-action catalog, parser/help fallback, and test-profile registry fallback have been removed; the published release bundle is the only authored-command authority.

`COMMAND_DEFINITION` revisions accept the first typed schema, reject malformed metadata before persistence, and cannot change after version publication. Full-version publication snapshots ordered validated definitions into the immutable release bundle, and the Game Design control-plane digest changes when their command contract changes. The bundle currently carries command metadata, aliases, and the first typed self-targeted execution effect; top-level targeting, cost, and tick-relative cooldown metadata remain future work beyond the release-admitted `APPLY_ACTION_STATE` v1 path. `APPLY_ACTION_STATE` v1 is validated at revision and publication time for self-targeting, effect-idempotent replay, bounded duration, and shared-effect-engine modifier grammar. The first live executor supports only release-admitted self-targeted `APPLY_ACTION_STATE` v1; malformed, unsupported, or stale snapshots fail closed before durable execution.

The broader authored-action model is not complete. The locked expansion boundary is one version-scoped typed definition seam with implicit `SOURCE` targets and bounded keyed `ActionTargetSet` declarations that reference release-pinned reusable `TargetSelectionPolicy` values. The remaining model distinguishes a reusable `TargetingPolicy` that returns eligible candidates from `TargetSelectionPolicy`, which supplies cardinality and typed selection strategy; target sets retain required/optional behavior and optional player-input slots. Future costs, tick-relative cooldowns, durations, ordered effect steps, and additional effect kinds must remain on the admitted definition snapshot and shared effect/timing boundary rather than per-command scripts or mutable runtime registry reads.

### Publication, Temporal Workflows, and Release Attestation

The parent `08` family remains in progress as a family map. Its current child boundaries are `08.1` publish/release attestation, `08.2` asset lifecycle, and `08.3` launch/activation complete for their recorded implementation boundaries; `08.4` script/plugin publication and `08.5` world-design mutation are complete at their current seams. The parent still has a pending `./gradlew linkCheck lintMarkdown` validation item, and later expansions remain follow-through rather than evidence that these current seams are absent.

Full-version publish and script-patch publish use explicit participant matrices and one durable publish-attempt framework. Each attempt persists a `publish_attempt` row and participant observations keyed by `publishWorkflowId`; Game Design exposes `GetDesignControlPlaneDigest`, and `published_release_bundle` records `participantDigests[]`. Required full-version participants are World Management, Entity Management, Game Logic, Automation & Scripting, and the Game Design control plane. The live gate fails closed on missing content digest, blank or unequal participant `appliedCommitId`, wrong scope, unsupported digest schema, unavailable participant, or cross-participant token mismatch. Successful observations establish a Game Design-owned `publish_recorded_participant_digest` baseline keyed by `(tenantId, publishType, participantKey, appliedCommitId)`; later scope, schema, or content-digest drift returns typed failures including `PARTICIPANT_UNAVAILABLE`, `PARTICIPANT_SCOPE_MISMATCH`, `UNSUPPORTED_DIGEST_SCHEMA`, `APPLIED_COMMIT_MISMATCH`, and `RECORDED_CONTENT_DIGEST_MISMATCH`. Participant services currently use synthetic scope tokens rather than the actual target commit, and World emits schema `2` while the gate accepts only schema `1`, so the live framework does not yet satisfy canonical same-commit publish convergence.

Successful full-version publish writes one immutable first-pass `published_release_bundle` row after all live gates pass. It includes `attestationSchemaVersion`, `publishWorkflowId`, `manifestHash`, `requiredManifestAssetKeys[]`, version identity, publish timestamp, observed participant digests, and the admitted command snapshot. The row does not yet carry the canonical top-level target `commitId`, `manifestSchemaVersion`, or applicable `artifactDigests[]`, so it is a minimal release-admission substrate rather than the complete canonical attestation shape. Asset export or attestation persistence failure fails closed and best-effort cleans exported assets. `GetPublishedReleaseBundle` is the canonical read surface and returns `NOT_FOUND` for missing attestation; it rejects unsupported attestation schemas. Launch, prepared-world activation, and repair independently apply the same unsupported-schema and missing-attestation hard-fail rules. Current World template rows (`region`, `zone`, `room`, `room_exit`, `generation_rule`) and Entity template rows (`items`, `npcs`, `crafting_recipes`) carry `version_id`; draft digests query those version-scoped rows, and existing local/dev rows default to version `1`.

Game Design hosts the durable `publish` Temporal workflow family for full-version publish/release-attestation orchestration. `PublishVersion` remains synchronous at the caller boundary but requires stable `publish_request_id`, making retries use one caller-visible Temporal business key. `GetPublishedReleaseBundle` exposes workflow identity and execution status without creating a second operator workflow API. Script-patch readiness, `onLoad`, rollout, and supersede flows use Temporal where they require durable waiting, retries, and operator visibility; gameplay-time script execution and tick handoff remain outside Temporal.

### Published Asset Lifecycle

Game Design is the sole writer to the shared published asset store. The persisted `version_asset_artifact` row carries `artifactState`, `stateEpoch`, `manifestHash`, exact exported manifest asset keys, frozen `exportedVersionNumber`, workflow identity, last error, and update timestamp. Full-version publish transitions asset state through `EXPORTED_UNATTESTED` and `PUBLISHED`; `GetVersionAssetArtifactState` is the authoritative proof read. `RepairPublishedVersionAssets` requires release-bundle and artifact-state proof and fails with `REPAIR_ATTESTATION_MISMATCH` unless rebuilt bytes reproduce the attested manifest hash and required key set.

The lifecycle also exposes `TombstoneVersionAssets`, `CanDeleteVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `GetVersionAssetPurgeStatus`. Purge records `version_asset_purge_workflow` rows and uses a CAS workflow rather than manual check-then-delete. Eligibility fails closed unless an existing version is `RETIRED`, and blocks on a dangling release bundle, live launch descriptors, or approved template remap sets. Deterministic failure reasons include `VERSION_ASSET_NOT_DELETABLE`, `ASSET_ARTIFACT_STATE_CONFLICT`, `REPAIR_ATTESTATION_MISMATCH`, `PURGE_WORKFLOW_NOT_FOUND`, and `PURGE_FINALIZATION_CONFLICT`. Finalization uses the frozen exported version number rather than rereading mutable version state. No normalized `version_asset`/`revision_asset` tables or separate derived-artifact export substrate exist yet.

### Launch, Activation, Cutover, and Termination

Game Design exposes deterministic `ResolveLaunchDescriptor` keyed by `(tenantId, gameTemplateId, controlPlaneRequestId)` and persists one descriptor per key. Same-request retries return the same descriptor or deterministic failure; conflicting request-ID reuse is rejected. The descriptor freezes `versionId`, `scriptPatchVersion`, `runtimeFlags`, `generationConfigRevision`, `versionStateEpoch`, `releaseBundleRef`, and approved replacement `remapSetId` where required. Game Design also exposes `GetVersionState` and `CompareAndSetVersionState`, and persists approved remap sets through `CreateTemplateRemapSet`, `ApproveTemplateRemapSet`, and `GetTemplateRemapSet`.

`StartSession` resolves the descriptor and, before creating `game_instances`, verifies release attestation, published asset artifact proof including manifest hash and required keys, authoritative version state, and pinned script-patch readiness. The typed launch-preflight failures include `TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED`, `INVALID_TEMPLATE_CONFIGURATION`, `SCRIPT_PATCH_OVERRIDE_CONFLICT`, `SCRIPT_PATCH_NOT_READY`, `RELEASE_BUNDLE_NOT_FOUND`, `RELEASE_ATTESTATION_MISMATCH`, `VERSION_STATE_EPOCH_STALE`, and `LAUNCH_REMAP_REQUIRED`. It persists descriptor identity and proof fields on `game_instances`. World Management exposes `PrepareWorldInstance`, `ActivatePreparedWorldInstance`, and `FailPreparedWorldInstance`; its instance-backed `world_instance`, `region_instance`, `zone_instance`, `room_instance`, and `room_instance_exit` rows are keyed by `(tenantId, gameInstanceId)` and activation revalidates release, asset, and epoch proof. Start-up failure marks prepared world state `FAILED_PRE_ACTIVATION`.

Game Session exposes `ValidateInstanceCutoverCompatibility`, `PrepareVersionUpgrade`, and `ExecutePreparedVersionCutover`. Preparation freezes target descriptor identity, approved remap ID, participant results, and checked-at time. Logging & Admin exposes the bounded compatibility read without first creating a preparation row. Cutover requires a matching durable preparation, revalidates it against the current pointer and replacement instance, performs a CAS-guarded pointer move, records the preparation ID in audit history, and makes retries of the same executed request idempotent. Successful preparation records replacement instance, resulting pointer version, execution timestamp, and request ID.

World cutover validation requires a cutover-eligible source lifecycle and retained `region_instance`, `zone_instance`, and `room_instance` topology. Entity validation covers tenant-surviving `character`, `inventory`, `character_equipment`, and `character_friend` plus instance-scoped containment; current `S1` survivors are accepted and current `S2` inventory/equipment requires the frozen approved remap ID. World Management exposes `GetWorldInstanceLifecycle` and `TerminateWorldInstance`, using the same `lifecycleEpoch` fence for termination and activation. Entity Management exposes `CleanupRuntimeInstance` for canonical room-ground containment cleanup. World events use `(tenantId, gameInstanceId)` through `region_instance`, and termination hard-deletes World-owned runtime rows before reporting `TERMINATED`.

Session stop deletes admission state before driving world termination and only finalizes local `STOPPED` after World reports `TERMINATED`; failure leaves the session draining rather than restoring resumability. `replaceExistingFirst` stages the losing session as `STOPPING`, removes admission, drives the same termination seam, then finalizes the old session and starts the replacement.

### Script Patch and Plugin Publication

Publication/readiness and runtime activation are separate. Game Design exposes `GetPublishedScriptPatchVersion` and, for plugins, upload-first `UploadPluginBundle`, `PublishPluginVersion`, `GetPublishedPluginVersion`, and tenant-scoped `ListPluginVersionStatuses`. Design-time publication reads are distinct from runtime `GetScriptPatchStatus` and `GetPluginStatus` readiness/activation reads; callers must join those views rather than invent a fused state. Upload parses signed plugin ZIPs, enforces bounded intake limits, verifies allowlisted Ed25519 signatures, extracts immutable manifest metadata, stores raw bytes in Game Design object storage, and records `SIGNATURE_VERIFIED` with deterministic `statusReason`. Publication requires the previously uploaded verified bundle, reuses its immutable metadata, verifies `abilitySchemaDigest` against the published `AUTOMATION_SCRIPTING` participant digest for the target base version, fails closed on blocked component policy, exports `assetRefs[]` into a plugin-version distribution manifest, and marks older published versions for the same `pluginId` as `SUPERSEDED`.

Design-time revoke is explicit through `RevokePluginVersion`. Upload verification, validation failure, publication, supersede, and revoke append durable tenant-scoped `PluginVersionStatusChanged`-equivalent rows exposed through `ListPluginVersionStatusEvents`, separate from instance-scoped runtime activation events. Runtime `SetPluginActiveVersion` consumes publication status, current Game Session runtime-instance metadata, immutable release participant digests, the authoritative built-in command-alias registry, and pinned script-patch bindings. It fails closed on non-`PUBLISHED` status, base-version or ability-schema mismatch, revoked signer metadata, blocked or missing component-policy decisions, unsupported built-in command aliases, or instance binding conflicts before mutating runtime state. Report-only component-policy decisions remain activatable.

### World Design Authoring Handoff

Game Design owns revision history, commits, version state, and publish orchestration; World Management owns authoritative Draft and Published world template rows. `SaveRevision` is version-scoped and carries typed `worldDesignMutation` payloads, including `WORLD_GENERATION_SUBTREE`, through the canonical World Management API. The first caller path saves revision history and applies the concrete change through that API rather than directly mutating World tables or treating opaque revision JSON as the authoritative world graph. The current seam permits only Draft version design-time writes; Published, Active, Failed, and Retired scopes are rejected.

### Validation and Proof

- Authored-command parser proof covers command-definition ID fallback without aliases: `./gradlew spotlessApply`, `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandParserTest'`, and `./gradlew linkCheck lintMarkdown`.
- Authored-help proof covers canonical/alias resolution, published-only reads, admin error mapping, template bridging, tenant isolation, and platform override behavior. The recorded validation is `./gradlew spotlessApply`, `bash dev-tools/validation/run-locked-gradle.sh :game-design-service:check :game-session-service:check -PfullCheck`, and `./gradlew linkCheck lintMarkdown`; Docker-backed integration and cross-service tests were skipped locally because Docker was unavailable, while runnable service tests, formatting, Checkstyle, SpotBugs, and documentation checks passed.
- Inventory/equipment was a documentation-only authority decision with no runtime behavior change; its recorded proof is `./gradlew linkCheck lintMarkdown`.
- Settings schema/reference generation and verification are available through `./gradlew updatePlatformSettingsDocs` and `./gradlew verifyPlatformSettingsDocs`; the source record also marks persistence, resolution, cache/invalidation, generation, and surfaced-domain coverage complete for their bounded slices.
- The parent `08` source record still contains an unchecked `./gradlew linkCheck lintMarkdown`; this is a parent-family review/validation checkpoint, not evidence that the completed child seams lack their recorded implementation.

## Active Gaps

- The planned settings-preset slice still needs the canonical preset concept, first preset families, precedence/override semantics, advanced-setting behavior, and tests proving expansion before operator overrides. There is no centralized operator-default/caps/preset resolver today.
- The broader authored-action model remains incomplete: the `ActionAdmissionTag` catalog and ordered `admissionTags` actor-admission metadata, generic target selection, costs, cooldowns, timing, ordered multi-effect steps, additional effect kinds, required/optional outcomes, and explicit cross-region compensation/refund behavior are not live.
- Rich help-authoring UI, localization-aware authored help, fuzzy or semantic search, related-topic graphs, moderation/versioning beyond ordinary authored-content ownership, and dynamic authored-action help integration remain deferred product work.
- The parent `08` family remains in progress. Participant digests still need actual target-commit provenance and coordinated schema adoption, and the release bundle still needs canonical target-commit, manifest-schema, and applicable artifact-digest fields. Future domain-template families must join the version-scoped digest gate; future release-bundle consumers must apply missing-attestation, unsupported-schema, and exact-asset-proof hard failures.
- Publish/version synchronization remains incomplete across downstream domain-owned schemas. Game Design owns revision history, version metadata, and publish orchestration, while Entity Management and World Management retain authoritative entity and world rows; the current participant observations and first World mutation handoff do not yet establish actual same-commit convergence or cover every future editor caller or template family.
- Asset lifecycle follow-through must widen purge eligibility to future `version_asset`, `revision_asset`, and normalized template/history reference tables, and later derived artifacts must use the same manifest/attestation/repair model.
- Launch/activation follow-through includes broader cutover and retirement consumers, later runtime-state families, exact Entity target-template validation once version-scoped entity-template tables exist, and replacement of remaining placeholder runtime topology/state rows.
- World design authoring currently has the first typed revision-to-World handoff, including the transactional applied-revision ledger, idempotent replay, expected aggregate/scope epoch checks, and reference/scope validation for supported mutations. Broader editor callers and concrete mutation coverage, publish-reconciliation replay, and actual digest commit-token convergence remain outside the current seam.

## To Discuss

- Decide preset family examples and preset composition/removal semantics without creating a second settings authority.
- Before broad authored-action implementation, decide ordered multi-effect step ordering, local atomicity, required versus optional outcomes, and `ON_EXECUTION` versus `ON_EFFECT_SUCCESS` cost/cooldown semantics.
- Confirm which product cases justify an explicit authored cross-region compensation/refund declaration; automatic refunds remain prohibited.
- Define additional authored-effect kinds and any new long-lived publication workflow only after the admitted-snapshot, replay, and Temporal boundaries are clear. Gameplay execution and tick handoff must remain outside Temporal.
- Deferred help design includes localization variants, related-topic graphing, fuzzy search, moderation/versioning, and creator-authored dynamic command-discovery metadata.

No competing current authority is recorded for settings, versioned release bundles, published assets, launch descriptors, or digest-gated runtime admission.

## Service and Contract Map

| Owner | Current responsibility | Primary contract boundary |
| --- | --- | --- |
| Game Design | Typed settings authority, overrides, generated metadata, revisions, authored definitions/help, releases, assets, launch descriptors, remap sets | Settings gRPC, revision/world mutation handoff, release bundle, digest/attestation, asset lifecycle, publication control plane |
| Common Platform Core | Shared settings metadata and persisted precedence resolution | Typed settings contracts consumed by runtime services |
| World Management | Authoritative version-scoped world templates, typed design mutation, world activation and termination | World design mutation, digest, lifecycle, topology, and publication handoff APIs |
| Entity Management | Release-owned entity/equipment runtime persistence and cutover compatibility/cleanup | Entity digest, remap compatibility, and runtime cleanup contracts |
| Game Session | Effective settings readback, authored command/help consumption, launch and cutover orchestration, runtime session lifecycle | Effective-settings endpoints, active command registry, launch/activation/cutover APIs |
| Game Logic | Effective communication settings and domain digest participation | Communication effective-settings read and publish digest contract |
| Automation Scripting | Script patch readiness and plugin runtime activation, distinct from publication truth | Patch/readiness workflow and runtime pin/activation contracts |
| Logging & Admin | Operator-facing compatibility and publication control-plane ingress | Canonical Game Design and runtime projections |
| Temporal | Durable publish/readiness workflow execution where long-lived waiting is required | Workflow identity/status surfaced through canonical reads |

## Source Evidence

The following records are the unchanged line-preserving transposition used as the audit backstop for the consolidated record above. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.

### source-02-12-task-list-movement-and-topology-settings-vertical-slice-20-30-56-64

#### Movement and Topology Settings Vertical Slice Task List - Settings authority, publication, and authoring ownership (source lines 20-30, 56-64)

##### Preserved Source Text: source-02-12-task-list-movement-and-topology-settings-vertical-slice-20-30-56-64

<!-- migration-source path="design/project-management/vertical-slices/02.12-task-list-movement-and-topology-settings-vertical-slice.md" lines="20-30, 56-64" sha256="95ad3b5582c9eb5987c1d2c3f342795cf95848fb0154ca393401496c4a80829f" heading-offset="3" -->
- The canonical layered ownership and precedence model for these settings is now captured in `design/architecture/system-architecture-settings-model.md`.
- `MoveCommandHandler` now reads the post-move redraw policy from typed config rather than treating automatic post-move `LOOK` as an unconfigurable constant.
- Current effective-config behavior is now explicit:
  - movement and topology settings now resolve through Game Session's first effective-settings read surface, merging `MovementProperties` and `WorldTopologyProperties` operator defaults with tenant/game overrides from the shared Game Design settings authority;
  - that bounded read surface is now inspectable at `/actuator/settings/effective` for a persisted session or a synthesized tenant/game-instance scope;
  - topology values are now part of the same effective-settings contract, with normalized subgroup payloads for `movementPostMoveView`, `worldTopologyScopeModel`, and `worldTopologyRegionBehavior`, even though broader runtime consumers remain future work;
  - region-capable topology settings now normalize to one canonical effective state so operator/debug inspection does not need to reason about inconsistent `scopeModel` and `regionsEnabled` combinations.
- Pre-06 completion state:
  - tenant/game override storage and shared effective resolution are now live for movement and topology through the shared Game Design settings authority and `common-platform-core`;
  - the generated markdown/schema settings reference is already live and includes the surfaced movement/topology keys;
  - later runtime use of topology for routing or communication scope resolution remains future feature work, not unfinished slice work.
<!-- source-gap: lines 31-55 -->
##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-20-30-56-64: 4. Persistence and Effective Config

- [x] Decide which movement/topology settings are tenant/game-configurable versus operator-capped.
- [x] Document how effective movement/topology config is currently resolved at runtime by the relevant gameplay services.

##### source-02-12-task-list-movement-and-topology-settings-vertical-slice-20-30-56-64: 5. Docs and Generated Reference

- [x] Add a generated or generation-ready settings reference for movement and topology domains.
- [x] Update movement and communication design docs to point at the surfaced settings categories where appropriate.
<!-- /migration-source -->

### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130

#### Authored Action Definition and Execution Model Vertical Slice - Authored definitions, publication, and release admission (source lines 1-37, 43-49, 51-88, 90-97, 100-130)

##### Preserved Source Text: source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130

<!-- migration-source path="design/project-management/vertical-slices/02.13.9-task-list-authored-action-definition-and-execution-model-vertical-slice.md" lines="1-37, 43-49, 51-88, 90-97, 100-130" sha256="a0680c6e555d3f3e33c990005c3359128e80676b2612eeef7d67d76438ced32f" heading-offset="3" -->
#### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Authored Action Definition and Execution Model Vertical Slice

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Goal and Status

Goal: define the canonical game-authored action model so later custom actions share one typed definition, validation, targeting, cost/cooldown, and execution path instead of bypassing the built-in command and effect architecture. Status: partially complete; the bounded release-admitted self-targeted `APPLY_ACTION_STATE` v1 path is live, while the broader authored-action model remains in progress.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Implementation Notes

The first canonical authored-command/runtime seam is now live on branch:

- `TextCommandInterpreter` resolves through a general `TextCommandRegistry` rather than a built-in-only branch tree;
- the active runtime registry resolves built-ins plus the authored definitions admitted from the current game instance's published release artifact;
- command alias ownership now also sits in command definitions, and the parser resolves canonical verbs through the active registry rather than a fixed built-in alias table;
- command definitions are now keyed by canonical `commandId`, so non-built-in commands do not have to pretend to own a `TextCommandType`;
- command definitions already carry the shared metadata authored commands will need later:
  - dispatch group;
  - stage requirement;
  - prompt policy;
  - primary action category;
  - ordered action-admission tags;
  - action-owned target selection;
  - source/ownership metadata;
- duplicate command-definition ownership is rejected when providers are aggregated, which prevents later authored providers from silently shadowing built-in definitions.
- duplicate alias ownership is also rejected when providers are aggregated, so later authored providers cannot silently shadow built-in command phrases.
- admitted authored definitions carry `TextCommandType.AUTHORED`, canonical `commandId`, aliases, stage, prompt policy, action category, ordered action-admission tags, action-owned target selection, and authored source ownership;
- parser support carries canonical authored `commandId` into `TextCommandPayload.AuthoredActionInvocation`;
- an authored dispatch handler executes through the same interpreter/dispatcher path and fails closed on unknown authored action ids;
- `HELP` discovery and direct lookup project authored topics from the admitted registry rather than treating authored verbs as hidden commands;
- first-pass metadata validation preserves targeting, tick-relative cooldown, and cost fields through the shared authored definition seam before execution.

The version-scoped declaration and artifact boundary is now live:

- `COMMAND_DEFINITION` revisions accept only the first typed schema, reject malformed metadata before persistence, and cannot be changed after their version is published;
- full-version publication snapshots the ordered validated definitions into the immutable published release bundle;
- the Game Design control-plane digest includes those definitions, so an attested version changes when its command contract changes;
- `GetPublishedReleaseBundle` returns the exact immutable definition snapshot to runtime consumers rather than exposing a mutable configuration source.
- Game Design now validates the first registered typed execution effect at revision and publication time: `APPLY_ACTION_STATE` v1 has self-targeting, effect-idempotent replay semantics, a bounded duration, and only shared-effect-engine modifier grammar.
<!-- source-gap: lines 38-42 -->
- the obsolete configuration-backed authored-action catalog, parser/help fallback, and test-profile registry fallback have been removed; published release bundles are the only authored-command authority.

Still future work under this slice:

- define how authored actions use targeting, cost, cooldown, and later effect/timing execution hooks beyond the first self-targeted action-state effect;
- register additional effect kinds or explicitly ordered multi-effect declarations through the same admitted-snapshot and replay boundary.

<!-- source-gap: lines 50-50 -->

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Checklist

- [x] Define target-state behavior and scope.
- [x] Complete the first version-scoped command-definition runtime consumption path for `APPLY_ACTION_STATE` v1.
- [ ] Complete the broader authored-action model for additional targeting, cost, cooldown, and effect semantics.
- [ ] Verify and close the broader authored-action follow-through.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Why This Slice Exists

FireMUD already has slices for command interpretation and action classification, but the platform still needs a dedicated model for authored actions themselves. Without that, future game-defined verbs risk becoming one-off scripts or handler-local exceptions.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Scope

- Define one canonical authored action model, including:
  - action identity;
  - command linkage;
  - classification/category;
  - requirements;
  - targeting rules;
  - costs/cooldowns;
  - execution/effect hooks.
- Define how authored actions plug into the built-in command registry/dispatcher architecture.
- Define how authored actions consume the future shared stats/effects foundation.
- Define how player-visible help/command discovery relates to authored actions.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Out of Scope

- A full authoring UI.
- Full scripting language design.
- Combat-specific balance data.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Known Design Considerations

- Built-in and authored commands should share one registry concept, not parallel dispatch paths.
- Action classification should provide the primary category and optional tags for activity/AFK semantics. A separate ordered `admissionTags` field controls DML-authored actor-disposition policy and must not reuse those activity tags.
- A reusable `TargetingPolicy` returns eligible candidates; actions have an implicit `SOURCE` target set plus bounded keyed `ActionTargetSet` declarations, each referencing a release-pinned reusable `TargetSelectionPolicy` for cardinality and typed selection strategy. Target sets retain their own required-or-optional behavior and optional declared player-input slots, so effects may target self and independently resolved selected sets without hardcoding action shapes.
- Costs, cooldowns, and durations should align with the later shared timing model.
<!-- source-gap: lines 89-89 -->

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Locked Direction

- Built-in and authored command definitions use one version-scoped registry seam.
- Authored commands should register definitions as data first, then dispatch through the same family-handler or later authored-action executor seam rather than bypassing interpretation.
- Command-definition ownership collisions are configuration/programming errors and should fail fast.
- The first authored-action model should define one canonical typed action shape rather than per-command script-local execution rules.
- Game Design's release bundle carries a DML `ActionAdmissionTag` catalog. Built-in and authored definitions publish a required ordered `admissionTags` list through the same registry; an explicitly empty list is valid. Feature availability and stage checks run before actor admission; an action is admitted only when none of its tags are denied by the resolved DML disposition/continuous-overlay policy.
<!-- source-gap: lines 98-99 -->

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Deferred Follow-ups

- [x] Expand the initial typed authored-action metadata by validating and carrying targeting, costs, and cooldowns through the shared definition seam.
- [x] Replace the bounded notice-only executor with the later shared action/effect/timing substrate when those slices land.
- [x] Keep future authored-action growth on the same provider-backed `commandId` registry plus dispatcher seam without reintroducing built-in-only assumptions.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: To Discuss Before Broader Action Implementation

- Ordered multi-effect actions: define the DML step ordering, local atomicity boundary, and required-versus-optional step outcomes for effects bound to `SOURCE` and resolved target sets. Keep `ON_EXECUTION` and `ON_EFFECT_SUCCESS` cost/cooldown semantics aligned with that decision.
- Cross-region completion and compensation: confirm the product cases, if any, that warrant an explicit authored compensation/refund declaration after a committed source action receives a failed remote leg. Automatic refunds remain prohibited.
- Later target-set derivation and cycling target interaction are tracked in the shared effect-engine slice; do not grow authored-command parsing around one speculative targeting UX.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Current Status

- [x] Implement the bounded release-admitted `APPLY_ACTION_STATE` v1 path end-to-end.
- [ ] Implement the broader authored-action model end-to-end.
- [ ] Verify and close the broader authored-action follow-ups.

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Completion Evidence

- `TextCommandParser` now resolves authored and extension commands via command-definition ID fallback when alias metadata is missing:
  - [services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/TextCommandParser.java](../../../services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/TextCommandParser.java)
- Focused regression proof that authored command IDs resolve even without aliases:
  - [services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/TextCommandParserTest.java](../../../services/game-session-service/src/test/java/unit/net/firedevops/firemud/gamesession/command/text/TextCommandParserTest.java)

##### source-02-13-9-task-list-authored-action-definition-and-execution-model-vertical-slice-1-37-43-49-51-88-90-97-100-130: Validation

- `./gradlew spotlessApply`
- `./gradlew :game-session-service:test --tests 'net.firedevops.firemud.gamesession.command.text.TextCommandParserTest'`
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19

#### Publish and Script Patch Temporal Migration Vertical Slice - Publish and release-attestation workflow ownership (source lines 1-19)

##### Preserved Source Text: source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19

<!-- migration-source path="design/project-management/vertical-slices/02.20.3-task-list-publish-and-script-patch-temporal-migration-vertical-slice.md" lines="1-19" sha256="42bec76402a48bfbc1f8bcfb7006a4e6b23878f2fa68597661a067d92fbb60ae" heading-offset="3" -->
#### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19: Publish and Script Patch Temporal Migration Vertical Slice

##### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19: Goal and Status

Goal: migrate publish, release-attestation, and script patch readiness / rollout workflows onto Temporal so FireMUD’s other strongly workflow-shaped control-plane family stops depending on inline saga orchestration. Status: implemented at the current boundary.

##### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19: Scope

- move version publish / participant validation / release-attestation workflow orchestration to Temporal;
- move script patch readiness, `onLoad`, rollout, and supersede lifecycle orchestration to Temporal where those flows need durable waiting, retries, and operator visibility;
- keep gameplay-time script execution and tick handoff outside the workflow engine.

##### source-02-20-3-task-list-publish-and-script-patch-temporal-migration-vertical-slice-1-19: Current Boundary

Implemented at the current boundary:

- Game Design now hosts the durable `publish` workflow family for full-version publish / release-attestation orchestration;
- `PublishVersion` keeps the synchronous caller contract, and now requires a stable `publish_request_id` so the durable work runs under one caller-visible Temporal business key instead of a fresh internal UUID per retry;
- the canonical `GetPublishedReleaseBundle` read surface now exposes Temporal workflow identity and execution status for publish operators without inventing a second workflow API;
<!-- /migration-source -->

### source-02-9-task-list-platform-settings-model-vertical-slice-1-150

#### Platform Settings Model and Generated Config Docs Vertical Slice Task List - Platform settings model (source lines 1-150)

##### Preserved Source Text: source-02-9-task-list-platform-settings-model-vertical-slice-1-150

<!-- migration-source path="design/project-management/vertical-slices/02.9-task-list-platform-settings-model-vertical-slice.md" lines="1-150" sha256="4ebbac29f353510d86af5d2a29162d5c2a4daa8f84e8426aecf50417ab2ccebd" heading-offset="3" -->
#### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: Platform Settings Model and Generated Config Docs Vertical Slice Task List

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: Goal and Status

Goal: establish one canonical FireMUD settings model before the inventory and later gameplay slices add more hardcoded policy, so operator/bootstrap settings, tenant/game behavior settings, and generated documentation all grow from the same schema instead of drifting by service. Status: pre-`06` complete.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: Implementation Notes

- The first operator/file-env settings seams are now live in code rather than only described in docs:
  - `FiremudReconnectionProperties` in `common-data-runtime`
  - `FiremudCommandHistoryProperties` in `common-data-runtime`
  - `CommunicationProperties` in `game-logic-service`
  - `PresentationProperties`, `MovementProperties`, and `WorldTopologyProperties` in `game-session-service`
- Prompt ownership is now cleaner:
  - reconnect transcript/buffer policy remains under `FiremudReconnectionProperties`
  - prompt emission/presentation policy lives under `PresentationProperties`
- The first generation-ready domain references now exist for Game Session presentation and movement/topology settings through application defaults, configuration metadata, and service-level configuration docs.
- The canonical layered ownership and precedence model is now captured in `design/architecture/system-architecture-settings-model.md` instead of being only slice prose.
- The canonical model now also explicitly prefers defaults-first operator UX:
  - most deployments should start from a small number of preset baselines;
  - operators should override only the settings they actually need to change;
  - and advanced knobs should remain available without becoming mandatory setup burden.
- Configuration metadata generation is now enabled in the app services using the Spring Boot configuration processor.
- The first consolidated generated publication outputs now exist:
  - `design/architecture/generated/platform-settings-schema.json`
  - `design/architecture/generated/platform-settings-reference.md`
  - generated from surfaced Spring settings metadata plus `config/settings/platform-settings-publication.json`
- Current effective-config behavior is now explicit for the surfaced pre-`06` domains:
  - Game Design now owns the first bounded shared persisted override authority for `reconnection`, `communication`, `presentation`, `movement`, `world-topology`, and `command-history`;
  - `common-platform-core` now resolves one shared merged persisted override layer per scope by applying tenant overrides before game-instance overrides;
  - Game Session merges service-local operator defaults with that shared effective persisted layer for `presentation`, `movement`, `world-topology`, `reconnection`, and `command-history`, then exposes the result at `/actuator/settings/effective`;
  - Game Logic does the same for `communication`, including game-instance scope, and exposes the result at `/actuator/settings/effective/communication`;
  - the operator-default layer still lives in service-local typed properties rather than inside the shared authority;
  - invalidation is now explicit but still bounded/local: runtime readers use a short TTL cache plus per-scope refresh/evict operations, not a distributed push model;
  - there is still not yet a centralized cross-service operator-default/caps/preset resolver.
- The canonical pre-`06` platform model is now in place:
  - operator/bootstrap defaults remain service-local typed properties;
  - shared persisted tenant/game overrides exist in Game Design;
  - shared merged persisted effective resolution now exists in `common-platform-core`;
  - surfaced settings domains now publish generated schema/reference output from one source of truth.
- Later follow-on work remains intentionally separate:
  - named preset-baseline implementation in `02.9.1`;
  - richer admin/creator form metadata on top of the generated schema;
  - and any future broader centralized operator-default/caps/preset expansion beyond the current bounded pre-`06` settings model.

This slice is intentionally placed before `06`. Reconnection, prompts, communication, future topology-dependent `shout`, and later gameplay systems all need a consistent settings story. The target state is not “everything in files” or “everything in the database.” The target state is one layered settings model with clear ownership:

- bootstrap and infrastructure settings live in file/env-backed operator config;
- some operator-scoped settings may also support deliberate runtime override through admin/operator tooling rather than requiring redeploys for every operational tuning change;
- most operators should start from a preset baseline and only override the specific values they need, rather than hand-tuning many unrelated settings;
- gameplay and UX behavior settings live in database-backed tenant/game config;
- the effective configuration is a validated merge of defaults, preset baseline, operator bootstrap config, operator runtime overrides where supported, operator caps/defaults, and tenant/game overrides;
- generated docs and later admin/creator tooling come from the same typed schema metadata rather than hand-maintained wiki text.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 1. Canonical Settings Ownership Model

- [x] Re-read the relevant architecture docs and identify where settings are already implicitly split between:
  - Spring/file/env bootstrap config;
  - database-backed tenant/game state;
  - and hardcoded defaults in service code.
- [x] Document one canonical rule for where settings live:
  - operator/bootstrap/infrastructure settings in files or environment;
  - operator-scoped live overrides in admin/operator tooling where explicitly supported;
  - tenant/gameplay behavior settings in the database;
  - effective config as a layered merge with explicit precedence.
- [x] Make explicit that “player-visible/game-behavior policy” should prefer database-backed config, while “service boot/runtime wiring” should prefer file/env config.
- [x] Document the override precedence clearly enough that services and future tooling all use the same mental model, for example:
  - hardcoded safe defaults;
  - selected preset baseline;
  - operator file/env defaults and bootstrap settings;
  - operator runtime overrides where intentionally supported;
  - operator hard caps and bounds;
  - tenant/game overrides from the database;
  - with tenant/game values always constrained by operator caps where applicable.
- [x] Document that FireMUD should prefer strong defaults and sparse override usage so most operators do not need to manage many individual knobs.
- [x] Document that named preset baselines are the preferred operator experience for common deployment shapes, even though the first implementation may arrive after the shared settings authority.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 2. Scope and Grouping Rules

- [x] Define canonical domain groupings for settings, such as:
  - `reconnection`
  - `communication`
  - `presentation`
  - `movement`
  - `inventory`
  - and other later gameplay domains
- [x] Document three setting scopes:
  - operator-only;
  - operator-runtime-overridable;
  - tenant/game-configurable;
  - tenant/game-configurable within operator-enforced caps.
- [x] Explicitly forbid exposing raw internal Spring or transport constants directly as gameplay/admin settings unless they are deliberately promoted into the platform settings model.
- [x] Document how per-domain config files or documents should map into one validated effective schema instead of becoming unrelated blobs.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 3. Typed Schema and Metadata for Autodoc

- [x] Define the minimum metadata every surfaced setting must carry in the canonical schema, including at least:
  - stable key/path;
  - description;
  - default;
  - valid range or enum;
  - scope/owner;
  - whether hot-reloadable;
  - whether advanced;
  - and at least one example value.
- [x] Choose and document the target source of truth for that schema in code so generated docs and later admin/creator tooling read from one authoritative model.
- [x] Document the generated outputs expected from that schema, including at least:
  - Markdown or MkDocs docs;
  - machine-readable schema;
  - and later admin/creator form metadata.
- [x] Keep the generated-doc target honest: the slice should not hand-maintain a second parallel settings reference if the schema can generate it.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 4. First End-to-End Domain: Reconnection

- [x] Use `reconnection` as the first concrete end-to-end settings domain because it already spans player-facing policy, operator caps, reconnect buffers, and failover timing.
- [x] Define the first grouped reconnection settings categories, for example:
  - `reconnection.policy`
  - `reconnection.buffer`
  - `reconnection.failover`
- [x] Decide which reconnection settings are operator-only versus tenant/game-configurable, for example:
  - operator-only hard caps on retry ceilings and maximum stall windows;
  - tenant/game-configurable resume windows and screen-buffer sizing within caps;
  - with prompt re-render behavior delegated to the prompt/presentation settings domain rather than a duplicate `reconnection.prompt` subtree.
- [x] Document the canonical reconnect settings examples using the agreed behavior model:
  - stale resume falls through to fresh entry;
  - screen buffer excludes prompts;
  - fresh `LOOK` plus one fresh prompt after reconnect restore.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 5. Persistence and Runtime Resolution

- [x] Decide and document where tenant/game settings live in the database and how services resolve the effective settings they need at runtime without every service inventing its own ad hoc lookup path.
- [x] Document whether a shared Game Design or equivalent settings authority should own tenant/game behavioral config for the first pass, rather than scattering settings tables across unrelated services.
- [x] Define the first-pass caching and invalidation expectations for the first Game Session effective-settings read surface so services can use it safely without pretending every change is instantly hot-reloaded everywhere.
- [x] Keep the eventual settings authority compatible with named preset baselines that expand into ordinary effective settings before operator-specific overrides apply.
- [x] Keep the runtime contract bounded: do not turn the first slice into a full distributed config platform if a smaller authoritative settings read model will do.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 6. Documentation and Generated Reference

- [x] Add or refine the architecture docs so the settings ownership and layering model is described directly in the target-state flow, not as an implementation footnote.
- [x] Add one generated or generation-ready settings reference section for the first surfaced domain so the slice proves the autodoc direction instead of merely describing it.
- [x] Ensure the docs distinguish clearly between:
  - operator bootstrap/runtime settings;
  - tenant/game behavior settings;
  - and hardcoded defaults that are not yet surfaced.

##### source-02-9-task-list-platform-settings-model-vertical-slice-1-150: 7. Final QA Checklist

- [x] Verify the first surfaced settings domain can be traced cleanly from typed schema to runtime resolution to generated docs/reference without duplicate manual documentation.
- [x] Confirm the operator-versus-tenant boundary is explicit enough that future slices like `06` can add gameplay settings without reopening the architecture discussion.
- [x] Confirm the slice leaves the repo in a state where future settings domains can be added by following one repeatable pattern instead of inventing a new storage/exposure model each time.
<!-- /migration-source -->

### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46

#### Settings Presets and Operator Baselines Vertical Slice Task List - Settings presets and operator baselines (source lines 1-46)

##### Preserved Source Text: source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46

<!-- migration-source path="design/project-management/vertical-slices/02.9.1-task-list-settings-presets-and-operator-baselines-vertical-slice.md" lines="1-46" sha256="facb5f096120105f8dc32aaa94a9edb86e839d6351159dc458acebbd46e4c51e" heading-offset="3" -->
#### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46: Settings Presets and Operator Baselines Vertical Slice Task List

##### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46: Goal and Status

Goal: add the first real preset-baseline model on top of the platform settings architecture so most operators can start from a named deployment baseline and override only the settings they actually need to change. Status: planned.

This slice follows `02.9`. It should not build a full preset-management UI first. The main purpose is to make the shared settings authority and effective-resolution path support named preset baselines cleanly.

##### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46: Scope

- Named preset baselines for common operator/deployment shapes
- Preset precedence in the effective-settings merge model
- Defaults-first operator UX
- Explicit separation between preset baselines and later per-setting overrides

##### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46: Key Tasks

- [ ] Define the first canonical preset concept:
  - preset as a named bundle of predefined values;
  - operator-facing baseline only;
  - not a second parallel settings system.
- [ ] Decide the first intended preset families or deployment-shape examples without overfitting:
  - local/dev;
  - hobby-self-hosted;
  - SaaS/operator-managed;
  - or equivalent simplified baseline set.
- [ ] Make the precedence explicit in runtime resolution:
  - hardcoded safe defaults;
  - selected preset baseline;
  - operator bootstrap overrides;
  - operator runtime overrides;
  - operator caps;
  - tenant/game overrides.
- [ ] Ensure operator-specific overrides can still tweak individual values after preset selection.
- [ ] Keep preset handling compatible with the shared settings authority/read-model rather than implementing it as a one-off bootstrap-only hack.
- [ ] Document how advanced settings remain available without forcing most operators to touch them.

##### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46: Tests

- [ ] Add coverage proving a preset baseline expands into ordinary effective settings before operator-specific overrides are applied.
- [ ] Add coverage proving explicit operator overrides still win over preset-provided values.

##### source-02-9-1-task-list-settings-presets-and-operator-baselines-vertical-slice-1-46: Notes

- This slice is about settings-model usability, not a full admin UI.
- Presets should reduce operator complexity; they should not create a separate source of truth that can drift from the underlying settings schema.
<!-- /migration-source -->

### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61

#### Settings Authority and Persistence Vertical Slice Task List - Settings authority and persistence (source lines 1-61)

##### Preserved Source Text: source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61

<!-- migration-source path="design/project-management/vertical-slices/02.9.2-task-list-settings-authority-and-persistence-vertical-slice.md" lines="1-61" sha256="d3f4c9be3b851a8e139abf13ccfd3301eca275af8e40b69f196f9a511f312c7e" heading-offset="3" -->
#### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Settings Authority and Persistence Vertical Slice Task List

##### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Goal and Status

Goal: implement the first shared persisted settings authority for the already-surfaced pre-`06` domains so tenant/game behavior settings stop living only as service-local property seams and ad hoc file/env overrides. Status: implemented.

This slice follows `02.9`. It should stay bounded: one authoritative read/write ownership model for the current surfaced domains, not a general distributed config platform.

##### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Scope

- Shared persisted settings ownership for:
  - `reconnection`
  - `communication`
  - `presentation`
  - `movement`
  - `worldTopology`
- Database storage for tenant/game-scoped overrides
- Canonical service/API ownership for settings reads and writes
- Migration path away from service-local-only gameplay settings

##### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Key Tasks

- [x] Decide the owning authority for tenant/game behavioral settings, likely Game Design or an equivalent settings authority surface.
- [x] Define the persisted settings data model for the currently surfaced domains.
- [x] Add the first storage and service/API contract for reading and writing tenant/game settings overrides.
- [x] Keep operator/bootstrap settings separate from tenant/game behavioral settings rather than collapsing them into one table or blob.
- [x] Ensure settings domains remain domain-oriented rather than action-specific.
- [x] Wire the first bounded runtime consumers to read persisted overrides through that authority instead of only from service-local config.

##### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Tests

- [x] Add persistence/API tests for the first shared settings authority.
- [x] Add focused cross-service tests proving one surfaced domain resolves tenant/game overrides through the shared authority.

##### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Implementation Notes

- Game Design is now the first shared persisted authority for surfaced pre-`06` settings overrides.
- Tenant/game override rows live in `game_settings_override`, one row per `{tenantId, optional gameInstanceId, domain}` with JSONB payloads scoped to:
  - `reconnection`
  - `communication`
  - `presentation`
  - `movement`
  - `worldTopology`
- The first authority contract is gRPC-only and bounded:
  - `GetScopedSettingsOverrides`
  - `PutSettingsDomainOverride`
  - `DeleteSettingsDomainOverride`
- Operator/bootstrap settings remain separate:
  - operator defaults still come from service-local `firemud.*` typed properties;
  - tenant/game overrides now come from Game Design persistence;
  - runtime services still perform the final merge locally.
- Runtime consumers now using the authority:
  - Game Session effective settings for `presentation`, `movement`, and `worldTopology`
  - Game Session effective reconnect policy/buffer resolution, including screen-buffer retention
  - Game Logic effective `communication` defaults
- The old bounded file/env tenant/game override path in Game Session was removed rather than kept as compatibility scaffolding.

##### source-02-9-2-task-list-settings-authority-and-persistence-vertical-slice-1-61: Notes

- This slice is about ownership and persistence first.
- It does not need to solve every cache or hot-reload concern before the authority exists.
<!-- /migration-source -->

### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49

#### Effective Settings Resolution and Invalidation Vertical Slice Task List - Effective settings resolution (source lines 1-49)

##### Preserved Source Text: source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49

<!-- migration-source path="design/project-management/vertical-slices/02.9.3-task-list-effective-settings-resolution-and-invalidation-vertical-slice.md" lines="1-49" sha256="c2284c42faf41b6c1ba02fdbcf0f2b14d7510714214a19003eb17d484977a42d" heading-offset="3" -->
#### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49: Effective Settings Resolution and Invalidation Vertical Slice Task List

##### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49: Goal and Status

Goal: implement one bounded effective-settings resolution path so runtime services consume the same merged settings result instead of each service inventing its own merge logic. Status: implemented.

This slice follows `02.9.2`. It should stay focused on read resolution, precedence, and bounded cache/invalidation behavior for the surfaced pre-`06` domains.

##### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49: Scope

- Effective-settings merge rules for the current settings domains
- Resolution precedence:
  - hardcoded safe defaults
  - selected preset baseline
  - operator bootstrap overrides
  - operator runtime overrides
  - operator caps
  - tenant/game overrides
- Bounded cache/invalidation behavior
- Canonical runtime read surfaces for Game Session and Game Logic

##### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49: Key Tasks

- [x] Implement one shared effective-settings resolver/read model for the current surfaced domains.
- [x] Apply canonical precedence consistently across services for the currently implemented layers:
  - service-owned operator defaults;
  - tenant persisted overrides;
  - game-instance persisted overrides.
- [x] Replace existing ad hoc service-local merge paths where the shared resolver now exists.
- [x] Define bounded caching and invalidation behavior for runtime consumers.
- [x] Keep the runtime contract simple and authoritative rather than turning it into a general config-distribution platform.
- [x] Update operator/debug effective-settings surfaces to reflect the shared resolver.

##### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49: Tests

- [x] Add resolution tests proving the currently implemented precedence across operator defaults plus tenant/game persisted override layers.
- [x] Add focused cache/invalidation tests for the first shared read model.
- [x] Add focused service tests proving Game Session and Game Logic resolve the same scope model, including game-instance overrides where applicable.

##### source-02-9-3-task-list-effective-settings-resolution-and-invalidation-vertical-slice-1-49: Notes

- This slice should close the biggest remaining “settings are real but not unified” gap.
- Runtime consistency matters more than broad dynamic hot-reload sophistication here.
- The shared read model in this slice is intentionally bounded:
  - `common-platform-core` merges persisted tenant then game-instance overrides into one effective persisted layer;
  - runtime services still own the last merge against their typed operator defaults;
  - shared reader caching is local and TTL-bounded, with explicit per-scope `refresh` and `invalidate` operations;
  - there is no distributed invalidation bus or generalized config push system.
- Remaining `02.9.4` work is generated schema/reference output from the same typed settings model rather than further resolution machinery.
<!-- /migration-source -->

### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41

#### Generated Settings Schema and Reference Vertical Slice Task List - Generated settings schema (source lines 1-41)

##### Preserved Source Text: source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41

<!-- migration-source path="design/project-management/vertical-slices/02.9.4-task-list-generated-settings-schema-and-reference-vertical-slice.md" lines="1-41" sha256="af477187aa6dff6121818337c8a05928474f137178d529d26ae57cde8d6208b9" heading-offset="3" -->
#### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41: Generated Settings Schema and Reference Vertical Slice Task List

##### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41: Goal and Status

Goal: complete the first real generated settings reference flow so the platform settings schema can produce operator/admin-facing documentation and machine-readable metadata from one source of truth. Status: implemented.

This slice follows `02.9` and should build on the typed metadata already surfaced in the pre-`06` domains.

##### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41: Scope

- Generated machine-readable settings schema
- Generated operator/admin-facing reference output
- Canonical metadata coverage for surfaced settings
- Alignment between schema, docs, and runtime ownership model

##### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41: Key Tasks

- [x] Define the first generated schema output format for surfaced settings domains.
- [x] Generate one consolidated settings reference from the typed settings metadata/source of truth.
- [x] Ensure generated output carries:
  - key/path
  - description
  - default
  - valid range or enum
  - scope/owner
  - hot-reloadability
  - advanced flag
  - example value
- [x] Keep generated output aligned with the canonical ownership and precedence model rather than becoming a second hand-maintained reference.
- [x] Document how later admin/creator tooling should consume the same schema output.

##### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41: Tests

- [x] Add generation tests or validation checks proving surfaced domains appear in the generated outputs.
- [x] Add doc/schema validation so generated reference drift is caught automatically.

##### source-02-9-4-task-list-generated-settings-schema-and-reference-vertical-slice-1-41: Notes

- This slice is about making the settings model publishable and inspectable, not about building the full UI on top of it.
- Implementation ships `./gradlew updatePlatformSettingsDocs` and `./gradlew verifyPlatformSettingsDocs`.
- The generated outputs are checked in under `design/architecture/generated/`.
<!-- /migration-source -->

### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154

#### Game-Authored Help Storage and Layering Vertical Slice - Game-authored help storage (source lines 1-154)

##### Preserved Source Text: source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154

<!-- migration-source path="design/project-management/vertical-slices/04.6.1-task-list-game-authored-help-storage-and-layering-vertical-slice.md" lines="1-154" sha256="737844460698ef1f83ccdb779c5a02b6c7c011d308547c7d08e497785bec2a0f" heading-offset="3" -->
#### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Game-Authored Help Storage and Layering Vertical Slice

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Goal and Status

Extend the built-in `HELP` system with a canonical storage and lookup model for game-authored help topics so each game template can override or extend platform-default help content without changing code. Status: implementation complete, pending CI and review.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end-to-end.
- [x] Verify and close any follow-ups.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Implementation Notes

The built-in platform-owned `HELP` command is already live in `game-session-service` as a code-backed corpus for common platform topics such as login, play, movement, inventory, equipment, containers, and communication.

This slice is the follow-up that moves beyond code-backed platform defaults into:

- game-authored help storage;
- topic alias/tag lookup within game scope;
- deterministic precedence between game-authored topics and platform-default help.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Why This Slice Exists

The current built-in `HELP` rollout gives FireMUD a useful platform-default corpus, but it is intentionally code-backed and platform-owned. That is enough for onboarding and common command discovery, but not enough for real game authorship.

This follow-up exists because:

- games need help content for their own lore, commands, and systems;
- platform-default topics such as `LOGIN`, `PLAY`, and `MOVEMENT` should remain available even if a game has not authored anything yet;
- topic aliases/tags such as `MOVE`, `WALK`, and later game-specific synonyms need one deterministic lookup model;
- tenant/game ownership must be explicit so help data does not drift into global mutable state.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Scope

- Define the canonical storage model for help topics and topic aliases/tags.
- Define platform-default versus game-authored layering and precedence.
- Define tenant/game ownership boundaries for authored help content.
- Define the read path the built-in `HELP` command should eventually use.
- Define the minimum admin/authored-content surfaces needed for the first storage-backed slice.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Out of Scope

- Rich authoring UI.
- Fuzzy semantic search.
- AI-authored or AI-resolved help answers.
- Localization-aware help authoring in the first pass.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Canonical Model

The first storage-backed model should center on:

- `canonicalTopicId`
- `title`
- `body`
- `tags` / aliases
- `tenantId`
- `gameTemplateId` as the authored-content ownership key
- `published` / lifecycle state

The platform should also keep a built-in default corpus that is not stored as mutable per-game data.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Layering and Precedence

The intended lookup order is:

1. game-authored topic matching the current game scope by canonical topic id
2. game-authored topic matching by alias/tag
3. platform-default topic by canonical topic id
4. platform-default topic by alias/tag
5. explicit unknown-topic result

This keeps platform defaults usable while allowing game content to replace or extend them cleanly.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Ownership Boundaries

- Platform-default help remains platform-owned and code/resource-backed.
- Game-authored help is scoped by `tenantId` and `gameTemplateId`. `gameInstanceId` is only the runtime bridge used by Game Session to read the admitted instance's template identity; it is not the authored-content key.
- Tenant ownership should remain explicit so a tenant cannot read or mutate another tenant's authored help corpus.
- If multiple templates can exist under one tenant, the read path must include `gameTemplateId` rather than only tenant scope.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Likely Service / Module Home

The first implementation should probably live in `game-design-service`, because:

- authored help content is game content, not runtime session state;
- game-design already owns game-scoped authored data and related CRUD/admin patterns;
- `game-session-service` should consume help data, not become the long-term authored-content store.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: First Implementation Shape

- Keep the current built-in `HELP` handler in `game-session-service`.
- Add a read client from `game-session-service` to `game-design-service` for help topic lookup.
- In `game-design-service`, add:
  - help topic entity/model
  - repository
  - service
  - bounded read API for topic resolution by canonical id or alias/tag within game scope
- Keep write/admin tooling minimal at first:
  - CRUD via game-design admin API
  - no rich editor or search UI yet

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Insertion Points

Primary future insertion points should be:

- `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/command/text/HelpCommandHandler.java`
  - current built-in help path
- `services/game-design-service`
  - new help-topic entity/repository/service/API
- existing game-design auth/admin surfaces
  - for bounded topic CRUD

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Suggested Read Path

The eventual runtime read path should be:

1. `HELP <topic>` arrives in `game-session-service`
2. built-in handler normalizes topic and current stage
3. if runtime has an admitted game instance with a template identity, query game-authored help by `tenantId` and `gameTemplateId` first
4. if no authored match, fall back to platform-default built-in corpus
5. return one canonical help output shape

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Expected Tests

- game-authored topic overrides platform-default by canonical id
- game-authored alias resolves correctly
- fallback to platform-default when game has no authored topic
- cross-tenant / cross-game isolation on reads
- unknown-topic behavior when neither layer has a match

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: What To Defer

- localization-aware help variants
- related-topic graphing
- fuzzy search
- moderation/versioning workflow beyond ordinary authored-content ownership
- dynamic command-discovery metadata integration for creator-authored actions

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Current Batch

- The canonical authored-help key is `tenantId + gameTemplateId`; Game Session resolves the template from the admitted runtime game instance before calling Game Design.
- Authored lookup uses normalized exact keys only, with canonical-topic precedence before alias precedence. Only published topics participate in player reads.
- Game Session falls back to its platform corpus when no admitted template exists, no published authored topic matches, or Game Design is unavailable.
- Game Design exposes authenticated gRPC topic resolution plus admin-gated put, list, and delete operations. The storage model rejects canonical/alias collisions so each normalized topic key resolves deterministically.
- Focused proof passed for Game Design canonical and alias resolution, published-only reads, admin error mapping, Game Session template bridging, tenant isolation, and platform override behavior.

##### source-04-6-1-task-list-game-authored-help-storage-and-layering-vertical-slice-1-154: Validation

- `./gradlew spotlessApply`
- `bash dev-tools/validation/run-locked-gradle.sh :game-design-service:check :game-session-service:check -PfullCheck`
- `./gradlew linkCheck lintMarkdown`

Docker-backed Game Design integration and Game Session cross-service tests were skipped locally because Docker was unavailable. The runnable service tests, formatting, Checkstyle, SpotBugs, and documentation checks passed.
<!-- /migration-source -->

### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59

#### Inventory and Equipment Settings Vertical Slice Task List - Inventory and equipment settings (source lines 1-59)

##### Preserved Source Text: source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59

<!-- migration-source path="design/project-management/vertical-slices/06.1-task-list-inventory-and-equipment-settings-vertical-slice.md" lines="1-59" sha256="cc7519d4a6c991034a44e99bdca70b843c418e767c8e809f9a9ff6cba44bbaeb" heading-offset="3" -->
#### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: Inventory and Equipment Settings Vertical Slice Task List

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: Goal and Status

Goal: classify inventory and equipment configuration by authority so versioned game-authored item facts remain DML-backed design data while genuinely cross-game runtime policy uses the platform settings model. Status: complete at the current boundary.

This slice followed `06` to prevent code-only policy from calcifying. The audit confirms that the live model already has the correct authority split: standard inventory command availability is controlled by the tenant/game `commandCapabilities.inventoryEnabled` setting, while versioned item and equipment facts are authored through the Game Design and Entity Management DML model. They must not be copied into a competing mutable settings domain.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: Authority Decision

- `commandCapabilities.inventoryEnabled` remains the current tenant/game setting for standard `INVENTORY`, room-ground, container, and equipment command availability.
- Equipment slot definitions, slot-group compatibility, body-layout membership, character body-layout keys, item equipment compatibility, and stackability/fungibility are versioned game-authored data. They are DML-backed, release-scoped design facts, not platform settings.
- Selector grammar, visible-item reference semantics, transfer/audit invariants, and holder ownership remain canonical platform contracts. They are not per-tenant switches.
- Future `inventory.*` or `equipment.*` settings require a real runtime behavior policy that is neither authored content nor a canonical protocol/invariant. Examples may include a later carry-capacity model or presentation-density policy, once those features exist.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: 1. Domain Inventory

- [x] Audit the inventory/equipment behavior introduced by `06` and classify each setting candidate as:
  - operator/runtime tuning;
  - tenant/game behavior policy;
  - or internal-only implementation detail.

Result: command availability is tenant/game policy; all current slot/body/compatibility/stackability values are versioned game-design data; selector and transfer semantics are canonical platform contracts. No current operator-only inventory tuning field has a demonstrated need.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: 2. Inventory Settings Surface

- [x] Decide the current canonical inventory settings surface.

The existing `commandCapabilities.inventoryEnabled` setting is the entire current inventory settings surface. `INVENTORY`, `GET`, `DROP`, item selector grammar, and room-ground transcript semantics remain one shared platform command contract rather than per-game settings.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: 3. Equipment Settings Surface

- [x] Keep body-layout and slot compatibility as game-defined data rather than platform-hardcoded truth.
- [x] Reject a duplicate `equipment.*` settings domain for body layouts, slot compatibility, and item compatibility.
- [x] Document the authority split without weakening Entity Management's authoritative runtime persistence model.

The existing versioned DML model is the canonical soft-coded equipment surface. A published game version supplies its slots, body layouts, and compatibility data; Entity Management persists and validates runtime bindings against that release-owned data.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: 4. Persistence and Effective Config

- [x] Decide which current inventory/equipment behavior is tenant/game-configurable versus operator-capped.
- [x] Document how effective configuration is resolved for gameplay validation and rendering.

Current tenant/game configuration resolves `commandCapabilities.inventoryEnabled` through the shared settings authority. Gameplay validation resolves versioned item/equipment data through the admitted release and Entity Management runtime model. There are no operator caps to expose until a concrete runtime behavior policy is introduced.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: 5. Docs and Generated Reference

- [x] Keep the generated settings reference limited to the existing inventory command capability.
- [x] Update the `06` docs to distinguish settings policy from versioned design data.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: 6. Final QA Checklist

- [x] Confirm later games can author slot/body-layout/compatibility data without rewriting inventory architecture.
- [x] Confirm inventory/equipment behavior uses the correct repeatable authority pattern: platform settings for policy, versioned DML for game design facts, and canonical contracts for protocol/invariants.

##### source-06-1-task-list-inventory-and-equipment-settings-vertical-slice-1-59: Validation

- Documentation-only authority decision; no runtime behavior changed.
- `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58

#### `08` Game Design Publishing and Runtime Activation - Game design publication and activation scope (source lines 1-58)

##### Preserved Source Text: source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58

<!-- migration-source path="design/project-management/vertical-slices/08-task-list-game-design-publishing-and-runtime-activation-vertical-slice.md" lines="1-58" sha256="7d55534ea803a15dedc3c5e4f2aeacd06ae30a1e9b88f9c90d0a975f6fad38ac" heading-offset="3" -->
#### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58: `08` Game Design Publishing and Runtime Activation

Goal: translate FireMUD's already-rich design-time versioning, asset publication, and launch-control-plane architecture into one explicit slice family so publish, attestation, activation preflight, and patch/plugin rollout do not keep growing as scattered notes across Game Design, World Management, and runtime docs. Status: in progress.

##### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58: Implementation Notes

This domain is heavily designed already, but still under-sliced relative to its importance:

- Game Design owns version lifecycle state, publish orchestration, release manifests, and immutable release attestation.
- publish gating already has a clear target-state contract around draft digests, participant selection, and `published_release_bundle`.
- asset lifecycle rules, manifest integrity, tombstoning, repair, and purge safety are documented in detail.
- launch resolution and activation preflight are already specified around normalized template references, resolved launch descriptors, and attested release validation.
- script-only patch and plugin publication are already intentionally separate from runtime pinning and activation, but that separation is not yet represented as dedicated slice planning.
- design-time world mutation is now intentionally represented as `08.5` so editor saves, procedural generation revisions, and spawn-binding authoring converge on one typed World Management API surface rather than leaking into opaque Game Design revision JSON or ad hoc World endpoints.

The problem is not missing architecture. The problem is that the architecture still lacks a coherent vertical-slice family, which makes implementation planning look thinner than the real target-state contract.

That is no longer just planning work: the family is now active in code.

- `08.1` now has a live immutable release-bundle attestation seam in `game-design-service`;
- the canonical publish-attempt / participant-observation framework is also now live;
- script-patch publish already uses that same framework with real Automation & Scripting plus Game Design control-plane digests;
- full-version publish now also uses the real domain participant matrix instead of placeholder missing-participant failures, while remaining follow-through is recorded-digest comparison and deeper target-state data modeling rather than absent participant coverage.
- `08.5` is complete at the current boundary and is the strongest doc-first review candidate in this family;
- `08.1` through `08.3` are materially real but still have meaningful remaining implementation follow-through and should be treated as in-progress slices rather than frozen review surfaces.
- `08.4` and `08.5` are now complete at their current canonical seams and are reasonable review entrypoints for publication-boundary truth and world-design mutation truth respectively.

This parent doc is intentionally a family map, not a claim that the whole `08.x` domain is finished enough to review without code.

##### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58: Why This Slice Exists

Without a dedicated family here, implementation pressure will keep leaking into unrelated slices:

- publish safety gets buried under generic runtime hardening;
- asset lifecycle gets treated like generic storage plumbing;
- activation preflight gets spread across Game Session, World Management, and Game Design docs without one bounded delivery plan;
- script patch and plugin publication risk being treated as "scripting runtime work" instead of design-time control-plane publication and rollout visibility.

This family is the canonical home for that work.

##### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58: Target State

- full-version publish uses explicit digest-gated participant selection and ends with one immutable release attestation row.
- published assets and derived artifacts move through one canonical Game Design-owned export/manifest lifecycle with fail-closed integrity and purge rules.
- instance launch resolves one immutable launch descriptor before any runtime rows are created, and activation preflight validates the attested release rather than reconstructing state ad hoc.
- script-only patch publication and plugin publication remain distinct from runtime pinning/activation, with clear readiness and rollout visibility.

##### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58: Child Slices

- [08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md](../vertical-slices/08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md)
- [08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md](../vertical-slices/08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md)
- [08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md](../vertical-slices/08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md)
- [08.4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice.md](../vertical-slices/08.4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice.md)
- [08.5-task-list-world-design-mutation-api-surface-vertical-slice.md](../vertical-slices/08.5-task-list-world-design-mutation-api-surface-vertical-slice.md)

##### source-08-task-list-game-design-publishing-and-runtime-activation-vertical-slice-1-58: Validation

- [ ] `./gradlew linkCheck lintMarkdown`
<!-- /migration-source -->

### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108

#### Publish Digest Gating and Release Attestation Vertical Slice - Publish digest gating and release attestation (source lines 1-108)

##### Preserved Source Text: source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108

<!-- migration-source path="design/project-management/vertical-slices/08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md" lines="1-108" sha256="366f5f4199f682ee51673e220a18b9dee9c8259e2130a4a82e86141bb579ec96" heading-offset="3" -->
#### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Publish Digest Gating and Release Attestation Vertical Slice

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Goal and Status

Goal: make full-version publish succeed only when all required draft digests and control-plane digests converge for the target commit/version scope, and finish by writing one immutable `published_release_bundle` attestation that activation, rollback-preflight, repair, and audit workflows treat as the canonical release truth. Status: complete for the current publish/launch/activation/repair boundary.

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Why This Slice Exists

FireMUD already has strong target-state publish contracts, but they currently live mostly in architecture docs. This slice exists to turn that into one bounded delivery plan instead of letting publish safety arrive piecemeal through service-local implementations.

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Implementation Notes

The target-state contract is now sharper in the architecture docs, and the first canonical release-bundle substrate is live in `game-design-service`:

- full-version publish no longer relies on a best-effort after-commit asset export hook;
- Game Design now writes an immutable `published_release_bundle` row for successful full-version publish;
- Game Design now exposes a read-only `GetPublishedReleaseBundle` gRPC surface;
- the first attested fields include `attestationSchemaVersion`, `publishWorkflowId`, `manifestHash`, `requiredManifestAssetKeys[]`, version identity, and publish timestamp;
- publish now fails closed if asset export or release-bundle persistence fails, and it best-effort cleans exported assets on failure instead of leaving "published" versions with no attestation.

The next canonical framework layer is now also live:

- publish now records one durable `publish_attempt` row plus `publish_attempt_participant_digest` observations keyed by `publishWorkflowId`;
- Game Design now exposes `GetDesignControlPlaneDigest` over gRPC for both full-version and script-patch publish scopes;
- `published_release_bundle` now carries the observed `participantDigests[]` in addition to `manifestHash`;
- script-patch publish now routes through the same canonical publish-attempt and participant-observation framework instead of a separate ad hoc control path.

The full-version participant matrix is now live end to end:

- World Management, Entity Management, Game Logic, Automation & Scripting, and Game Design control plane all now answer the full-version digest gate instead of leaving placeholder `UNIMPLEMENTED_DIGEST_PARTICIPANT` failures in the canonical publish path;
- the publish gate now fails closed on unsupported digest schema, missing content digest, missing applied commit, wrong scope, or cross-participant applied-commit mismatch instead of only checking for generic participant success;
- Game Design now consumes the domain digests through dedicated gRPC clients instead of a local “known missing participants” stub path.

The expected-digest and typed-error layer is now also live:

- successful publish now records a Game Design-owned `publish_recorded_participant_digest` baseline keyed by `(tenantId, publishType, participantKey, appliedCommitId)` instead of treating same-attempt observations as the only durable proof;
- subsequent publish attempts now fail closed when the same recorded commit reappears with a different scope, digest schema, or content digest for any required participant;
- `PublishVersion` and `PublishScriptPatchVersion` now surface typed control-plane failure codes such as `PARTICIPANT_UNAVAILABLE`, `PARTICIPANT_SCOPE_MISMATCH`, `UNSUPPORTED_DIGEST_SCHEMA`, `APPLIED_COMMIT_MISMATCH`, and `RECORDED_CONTENT_DIGEST_MISMATCH` instead of collapsing every gate failure into one generic publish error row.

The first attestation-consumer hard-fail layer is now also live:

- `GetPublishedReleaseBundle` now distinguishes missing attestation as `NOT_FOUND` instead of collapsing it into generic invalid-input handling;
- `GetPublishedReleaseBundle` now also fails closed on unsupported release-bundle attestation schema instead of returning a bundle consumers cannot safely interpret;
- launch-descriptor resolution now fails closed when the release-bundle attestation schema is not one the current service understands;
- Game Session launch preflight and World Management prepared-world activation now independently reject unsupported release-bundle attestation schemas before using manifest, release-bundle, or artifact-state fields;
- published-version asset repair now proves both the attested `manifestHash` and the attested `requiredManifestAssetKeys[]` before accepting the repair result, so repair cannot silently pass on a differently shaped export.

The target-state contract still goes further than the current code:

- full publish now explicitly requires same-commit convergence across every required digest participant, not just individually valid digest payloads;
- the docs now explicitly reject any half-complete launchable state between "digests matched" and "immutable release attestation row committed";
- `GetPublishedReleaseBundle` absence is now explicitly treated as non-launchable even if an in-flight publish workflow has otherwise progressed past digest checks;
- exact-bytes repair is now explicitly separated from any future re-attestation workflow so normal repair tooling cannot silently rewrite `published_release_bundle`.

The version-scoped digest-input layer is now also live for the current World and Entity template tables:

- World Management template rows (`region`, `zone`, `room`, `room_exit`, `generation_rule`) and Entity Management template rows (`items`, `npcs`, `crafting_recipes`) now carry `version_id` alongside `tenant_id`;
- World and Entity `GetDraftDesignDigest(versionId)` implementations now query those version-scoped rows instead of hashing all tenant draft rows while pretending the response is scoped;
- World activation materializes topology from the requested launch descriptor's `(tenantId, versionId)` template graph instead of all tenant templates;
- existing local/dev rows default to version `1`, preserving bootstrap behavior while making non-default versions structurally representable.

The biggest current implementation gap is now narrower and more explicit:

- downstream launch, activation, and repair consumers now have the first missing-attestation, unsupported-attestation, and asset-proof hard-fail coverage. Future work is extending this same contract to later consumers as they are introduced, not fixing a known gap in the current launch/activation path.

What remains is normal future expansion: new domain-template families and new release-bundle consumers must join the same canonical framework when they are introduced. There is no known current-boundary publish-attestation gap left in this slice.

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Scope

- full-version publish participant selection and fixed digest-gate matrix
- domain-service `GetDraftDesignDigest` convergence
- Game Design `GetDesignControlPlaneDigest` convergence
- publish failure behavior for missing/mismatched digests or unsupported digest schema
- immutable `published_release_bundle` write after all gates pass
- `GetPublishedReleaseBundle` as the canonical read surface for downstream launch and repair workflows

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Out of Scope

- script-only patch publish behavior beyond the narrow control-plane distinctions captured in `08.4`
- asset export byte lifecycle details beyond the attested `manifestHash` and typed artifact digests captured here
- runtime instance creation or world activation sequencing beyond the attestation read contract

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Locked Direction

- publish participant selection is fixed by publish type and must not vary implicitly at runtime.
- the implementation should use one canonical durable publish-orchestration framework now, not a service-local special case for one or two participants.
- publish orchestration should persist one durable publish-attempt record plus participant-observation rows keyed by `publishWorkflowId`, rather than holding digest comparisons only in memory or only in logs.
- participant selection should be explicit by publish type from day one (`FULL_VERSION`, `SCRIPT_PATCH`, later others) even if some matrices are narrower than others.
- publish must fail closed on digest mismatch, missing digest, or unsupported digest schema.
- publish must also fail closed on wrong target/version scope or any other participant response that cannot be proven to correspond to the requested publish target.
- Game Design control-plane digest is a first-class gate, not optional metadata.
- script-patch publish should use the same orchestration substrate as full-version publish, with a different participant matrix rather than a separate hand-shaped implementation path.
- `published_release_bundle` is the canonical release attestation; activation and repair must not reconstruct release truth from scattered service-local tables.
- full-version publish is not complete until the immutable attestation row exists for the target `(tenantId, versionId)`.
- downstream launch/repair/rollback-preflight reads must hard fail when `GetPublishedReleaseBundle` is absent; they must not fall back to reconstructing publish truth from `version`, object-store contents, or service-local tables.
- downstream attestation consumers must also fail closed when the attestation schema is unsupported.

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Future Follow-Through

- Extend the same version-scoped digest discipline to later domain-template families as they are introduced, without changing the canonical gate contract.
- Keep future release-bundle consumers on the same missing-attestation, unsupported-schema, and exact-asset-proof hard-fail pattern already used by launch, activation, and repair.

##### source-08-1-task-list-publish-digest-gating-and-release-attestation-vertical-slice-1-108: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end for the current boundary.
  The canonical publish-attempt framework, Game Design control-plane digest RPC, full-version and script-patch participant coverage, release-bundle participant-digest attestation, recorded-digest comparison, typed publish-gate failures, typed missing-attestation reads, unsupported-schema rejection across current launch/activation consumers, exact manifest-key repair proof, and current World/Entity version-scoped digest inputs are now live. Remaining work is later domain-template coverage and future consumer enforcement, not a missing current launch/activation proof.
- [x] Verify and close current-boundary follow-ups.
<!-- /migration-source -->

### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67

#### Published Asset Manifest and Purge Lifecycle Vertical Slice - Published asset lifecycle (source lines 1-67)

##### Preserved Source Text: source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67

<!-- migration-source path="design/project-management/vertical-slices/08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md" lines="1-67" sha256="a5b7b877d4325c6b18949eca565770322f0c19719a10bbf488132a9499d4d15a" heading-offset="3" -->
#### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Published Asset Manifest and Purge Lifecycle Vertical Slice

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Goal and Status

Goal: make Game Design's published asset/export path one canonical lifecycle covering version-scoped manifest export, derived-artifact publication, integrity attestation, tombstoning, exact-bytes repair, and race-safe asset purge so published releases never depend on ad hoc object-store conventions or manual cleanup steps. Status: complete for the current implementation boundary.

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Why This Slice Exists

Asset storage is already documented well enough to be dangerous if implemented inconsistently. This slice isolates the actual publication lifecycle so object-store bytes, manifest integrity, and purge eligibility do not become spread across local scripts, domain-service shortcuts, or operator guesswork.

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Implementation Notes

The target-state contract is now sharper in the architecture docs:

- Game Design asset docs now explicitly require proof/read APIs for artifact lifecycle, exact-bytes repair, and purge workflow status rather than letting operators infer state from raw tables or bucket listings;
- repair and purge now have a minimum deterministic failure vocabulary (`VERSION_ASSET_NOT_DELETABLE`, `ASSET_ARTIFACT_STATE_CONFLICT`, `REPAIR_ATTESTATION_MISMATCH`, `PURGE_WORKFLOW_NOT_FOUND`, `PURGE_FINALIZATION_CONFLICT`);
- exact-bytes repair now explicitly requires both `GetPublishedReleaseBundle` and `GetVersionAssetArtifactState` proof before rewriting bytes.

The first canonical implementation cut is now live in `game-design-service`:

- `version_asset_artifact` is now a persisted control-plane row carrying `artifactState`, `stateEpoch`, `manifestHash`, workflow identity, last error, and update timestamp;
- full-version publish now transitions asset state through `EXPORTED_UNATTESTED` and `PUBLISHED` instead of leaving object-store export as an untracked side effect;
- Game Design now exposes `GetVersionAssetArtifactState` as the authoritative proof read for the persisted artifact row;
- Game Design now exposes `RepairPublishedVersionAssets` and fails closed with `REPAIR_ATTESTATION_MISMATCH` when rebuilt bytes do not reproduce the attested `manifestHash`.
- the persisted artifact row now also carries the exact exported manifest asset-key set, so failed publish cleanup and later purge operate on exported proof rather than re-reading whatever draft assets the tenant happens to have now;
- Game Design now exposes `TombstoneVersionAssets`, `CanDeleteVersionAssets`, `BeginPurgeVersionAssets`, `FinalizePurgeVersionAssets`, and `GetVersionAssetPurgeStatus` over the same lifecycle substrate instead of leaving purge as a runbook-only contract;
- purge now records `version_asset_purge_workflow` rows so status, retry, and finalization failure are queryable without bucket inspection.
- deletion eligibility now fails closed on broader non-local truth that already exists today: if the version row still exists it must be `RETIRED`, and a dangling `published_release_bundle` without version state now blocks purge instead of being treated as an ignorable inconsistency.
- deletion eligibility now also fails closed on live Game Design references that already exist today: launch descriptors and approved template remap sets must no longer reference the version before purge can begin.
- the artifact proof row now freezes `exportedVersionNumber`, exposes it through `GetVersionAssetArtifactState`, and purge finalization uses that frozen export prefix instead of re-reading the mutable `version` row; this lets purge stay tied to exact export proof even if version state has already been removed after deletion eligibility.

The current implementation boundary is complete: the live system has no normalized `version_asset` / `revision_asset` tables or separate derived-artifact export substrate to wire in yet, so those concerns stay tracked as future follow-through for the slices that introduce those tables/artifacts rather than as unfinished work in this slice.

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Scope

- Game Design-owned publish/export of version-scoped assets and derived artifacts
- deterministic `manifest.json` generation and `manifestHash` integrity
- producer-to-publisher handoff for derived artifacts
- artifact lifecycle states such as staged, exported unattested, published, failed, and tombstoned
- exact-bytes repair requirements for Published and Active releases
- CAS-guarded asset purge eligibility and finalization

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Out of Scope

- broader publish digest gating beyond the attested manifest/artifact fields owned by `08.1`
- launch-descriptor or instance-creation preflight beyond the manifest/read contracts consumed there
- general frontend/browser asset UX

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Locked Direction

- Game Design is the sole writer to the shared published asset store.
- runtime consumers discover published assets and derived artifacts through the attested version manifest, not bucket-key conventions.
- published and active releases are immutable with respect to manifest bytes and attested content hashes.
- purge must remain a control-plane CAS workflow, not a manual "check then delete" sequence.
- derived artifacts exported outside producer-owned storage must pass through the same manifest and attestation model as other version assets.

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Future Follow-Through

- widen deletion-eligibility checks from today’s `version`, `published_release_bundle`, live launch-descriptor, and approved-remap truth to the later canonical `version_asset`, `revision_asset`, and broader normalized template/history reference tables as those rows land.
- keep derived-artifact producer handoff and manifest generation aligned with the same attested repair model.
- widen proof and repair coverage to the later derived-artifact attestation fields once those artifacts are exported through Game Design.

##### source-08-2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice-1-67: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end for the current implementation boundary.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79

#### Launch Descriptor and Activation Preflight Vertical Slice - Launch descriptor and activation preflight (source lines 1-79)

##### Preserved Source Text: source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79

<!-- migration-source path="design/project-management/vertical-slices/08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md" lines="1-79" sha256="ea102ef96ad025512402a584bee10d3eed49e38e3378f364bcabddef1bde9504" heading-offset="3" -->
#### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Launch Descriptor and Activation Preflight Vertical Slice

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Goal and Status

Goal: require template-driven instance creation to resolve one immutable launch descriptor and validate the attested release bundle before any persistent runtime rows are created, so activation and replacement-instance workflows stop depending on ad hoc template parsing, moving-target defaults, or scattered control-plane reads. Status: complete for the current implementation boundary.

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Why This Slice Exists

The architecture already says launch must fail before admission when template references, script-patch readiness, release attestation, or version state are unsafe. This slice turns that into one bounded implementation plan shared by Game Design, Game Session, and World Management rather than leaving it as a diffuse contract.

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Implementation Notes

The target-state contract is now sharper in the architecture docs, and the first canonical launch-descriptor/preflight substrate is live:

- `ResolveLaunchDescriptor` retries keyed by the same `controlPlaneRequestId` must return the same descriptor values or the same deterministic business failure rather than drifting to newer control-plane state;
- the docs now define a minimum launch-preflight failure vocabulary (`TEMPLATE_REFERENCE_PHASE_NOT_ENFORCED`, `INVALID_TEMPLATE_CONFIGURATION`, `SCRIPT_PATCH_OVERRIDE_CONFLICT`, `SCRIPT_PATCH_NOT_READY`, `RELEASE_BUNDLE_NOT_FOUND`, `RELEASE_ATTESTATION_MISMATCH`, `VERSION_STATE_EPOCH_STALE`, `LAUNCH_REMAP_REQUIRED`);
- `versionStateEpoch` is now explicitly part of launch proof, not advisory metadata;
- runtime services may consume a resolved descriptor only for the current launch attempt and must not persist it as a reusable "latest defaults" cache.
- Game Design now exposes `ResolveLaunchDescriptor` over gRPC and persists one deterministic `launch_descriptor` row per `(tenantId, gameTemplateId, controlPlaneRequestId)`;
- Game Design now owns a real version-lifecycle control-plane seam with `GetVersionState` and `CompareAndSetVersionState`; `versionStateEpoch` is no longer faked from release-bundle identity;
- launch-descriptor resolution now persists and returns `versionId`, `scriptPatchVersion`, `runtimeFlags`, `generationConfigRevision`, `versionStateEpoch`, and `releaseBundleRef`, and it rejects conflicting request-id reuse as deterministic idempotency-key misuse;
- launch-descriptor resolution now also surfaces typed `RELEASE_BUNDLE_NOT_FOUND` and `LAUNCH_REMAP_REQUIRED` outcomes instead of collapsing those cases into generic invalid-input handling;
- Game Design now persists explicit `version_template_remap_set` control-plane state, exposes `CreateTemplateRemapSet` / `ApproveTemplateRemapSet` / `GetTemplateRemapSet`, and resolves cross-version replacement launches onto exactly one approved `remapSetId` for the source/target version pair;
- Game Session `StartSession` now resolves launch descriptors, verifies the attested `published_release_bundle`, proves the matching published asset artifact state (`manifestHash` plus required manifest asset keys), and re-reads authoritative version state before any `game_instances` row is created;
- `game_instances` now persist the resolved launch-descriptor identity (`gameTemplateId`, `launchDescriptorId`, `versionId`, `releaseBundleId`, `versionStateEpoch`, `generationConfigRevision`, `remapSetId`) rather than carrying only a loosely interpreted runtime version string.
- World Management now exposes canonical activation lifecycle RPCs (`PrepareWorldInstance`, `ActivatePreparedWorldInstance`, `FailPreparedWorldInstance`) instead of the old raw `tenantId/versionId` placeholder path.
- World Management now persists `world_instance`, `region_instance`, `zone_instance`, `room_instance`, and `room_instance_exit` rows keyed by `(tenantId, gameInstanceId)` and revalidates release-bundle, published-asset artifact proof, and `versionStateEpoch` proof before activation opens; replacement-launch world rows now also freeze the resolved `remapSetId`.
- Game Session now exposes the first canonical `ValidateInstanceCutoverCompatibility` control-plane seam, resolving the target replacement launch descriptor first and collecting Game Design / World / Entity participant attestations into one cutover-preflight response.
- Game Session now also exposes `PrepareVersionUpgrade`, which persists one `prepared_version_upgrade` control-plane artifact containing the target launch-descriptor identity, frozen `remapSetId`, participant results, and checked-at timestamp for the requested source-instance -> target-version cutover attempt.
- Logging & Admin now exposes the bounded `ValidateInstanceCutoverCompatibility` read directly under the admission-pointer/version-upgrade operator surface, so operators can inspect the canonical source-instance -> target-version compatibility verdict without creating a durable preparation row first.
- Admission-pointer cutover now consumes that artifact instead of treating it as planning-only state: `SetAdmissionPointer` requires `preparedVersionUpgradeId` whenever a realm moves to a different `gameInstanceId` and rejects the swap unless the durable preparation still matches the current source pointer target and the replacement instance's frozen launch proof.
- Game Session now also exposes `ExecutePreparedVersionCutover`, which turns the prepared-upgrade proof into one canonical cutover operation: it revalidates the durable preparation against the current pointer and replacement instance, performs the CAS-guarded pointer move, records the same `preparedVersionUpgradeId` in audit history, and treats retries of the same already-executed request as idempotent reads of the resulting pointer state.
- `prepared_version_upgrade` no longer stops at preflight-only state after that cutover: once execution succeeds, the durable preparation record now also stores the replacement `gameInstanceId`, resulting pointer version, execution timestamp, and execution request id.
- World Management cutover validation is now stricter on the live `S3` substrate too: it requires a cutover-eligible source world lifecycle state and retained topology rows (`region_instance`, `zone_instance`, `room_instance`) rather than treating any lone `world_instance` row as sufficient.
- Entity Management cutover validation is now stricter too: it enumerates tenant-surviving `character`, `inventory`, `character_equipment`, and `character_friend` families as well as instance-scoped containment families, accepts current `S1` survivor rows (`character`, `character_friend`), and requires the frozen approved `remapSetId` whenever current `S2` inventory/equipment rows exist.
- Game Session `StartSession` now consumes the World Management activation seam rather than treating World Management as a ping-only dependency check, and failed start-up rollback now marks prepared world state `FAILED_PRE_ACTIVATION`.
- World Management now also exposes `GetWorldInstanceLifecycle` and `TerminateWorldInstance`, and termination uses the same `lifecycleEpoch` fence as activation instead of a separate ad hoc shutdown path.
- Entity Management now exposes `CleanupRuntimeInstance` so runtime-instance teardown removes room-ground containment rows through one canonical cross-service contract rather than operator-side cleanup guesses.
- World Management runtime events are now keyed by `(tenantId, gameInstanceId)` through `region_instance`, not template `region` rows, and termination hard-deletes World-owned runtime rows before reporting `TERMINATED`.
- Game Session `stopSession` now deletes admission state first, then drives the World Management termination seam, and only finalizes local `STOPPED` state after World reports `TERMINATED`; if world termination fails after admission is closed, the session remains draining instead of being silently restored to a live resumable state.
- `replaceExistingFirst` session replacement now stages the losing session as `STOPPING`, deletes its admission state, and drives the same World Management termination seam before finalizing the old row as `STOPPED` and the replacement row as `RUNNING`.

The current implementation boundary is complete: launch descriptor resolution, asset/release/version proof, world activation, prepared upgrade proof, canonical cutover execution, termination cleanup, and current World/Entity compatibility checks are live. Remaining work depends on future runtime-state families, version-scoped entity-template tables, and later cutover/retirement consumers, so it stays tracked as follow-through for those slices rather than unfinished work in this slice.

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Scope

- normalized template reference enforcement and phase gating
- deterministic `ResolveLaunchDescriptor` semantics keyed by `controlPlaneRequestId`
- attested release-bundle validation before instance creation
- launch-time validation of `versionStateEpoch`, `generationConfigRevision`, and pinned script-patch readiness
- control-plane ordering before `gameInstanceId` creation and world `PREPARING` state

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Out of Scope

- deeper replacement-instance remap semantics beyond the requirement that approved remap inputs be part of descriptor resolution when needed
- broader publish workflow internals covered by `08.1`
- asset lifecycle internals covered by `08.2`

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Locked Direction

- launch resolves one immutable descriptor before any persistent runtime provisioning begins.
- launch retries keyed by the same `controlPlaneRequestId` must reuse the same descriptor values.
- instance creation must fail closed when template-reference phase is not enforced, the release bundle is missing or unsupported, the target version state changed, or a pinned script patch is not ready.
- runtime services must not re-parse template JSON or resolve "latest" control-plane state mid-flight.

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Future Follow-Through

- extend the now-live approved-remap launch-descriptor substrate, persisted `PrepareVersionUpgrade` record, first cutover-preflight seam, and canonical execution seam into later cutover/retirement consumers.
- extend the now-live World Management lifecycle seam into broader cutover/replacement consumers so the same launch proof and lifecycle fencing are used outside the first `StartSession` and explicit stop/termination paths too.
- extend the now-live Game Design `GetVersionState` / `CompareAndSetVersionState` seam into later activation/cutover consumers so launch, replacement, and retirement-safe transitions share one control-plane fence.
- extend Entity Management from the current approved-remap identity fence into exact target-template reference validation once version-scoped entity-template tables exist.
- continue replacing placeholder runtime topology/state rows with later canonical instance-scoped runtime state families rather than leaving more activation work on tenant-scoped legacy tables.

##### source-08-3-task-list-launch-descriptor-and-activation-preflight-vertical-slice-1-79: Checklist

- [x] Define target-state behavior and scope.
- [x] Implement the slice end to end for the current implementation boundary.
  The first canonical launch-descriptor persistence, typed missing-attestation/remap-required launch outcomes, approved remap-set control-plane APIs, replacement-launch `remapSetId` resolution/persistence, Game Session and World Management asset-proof preflight, World Management activation lifecycle, instance-backed world/region/zone/room topology, runtime-scoped world-event cleanup, the first cross-service `ValidateInstanceCutoverCompatibility` seam, the first persisted `PrepareVersionUpgrade` record, canonical prepared cutover execution, and current Entity `S1` / `S2` compatibility fence are now live, but broader cutover/retirement consumers and exact Entity target-template validation are still open.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66

#### Script Patch and Plugin Publication Boundaries Vertical Slice - Script and plugin publication boundaries (source lines 1-66)

##### Preserved Source Text: source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66

<!-- migration-source path="design/project-management/vertical-slices/08.4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice.md" lines="1-66" sha256="9c5d10dfc91e2684ea09d9a9458a4e7763955d5527a86d25f695748e13136f05" heading-offset="3" -->
#### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Script Patch and Plugin Publication Boundaries Vertical Slice

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Goal and Status

Goal: make script-only patch publication and plugin publication first-class design-time control-plane workflows with clear readiness, compatibility, and rollout visibility boundaries, while keeping runtime pinning and activation as separate later actions rather than an implicit side effect of publication. Status: complete at the current boundary.

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Why This Slice Exists

The current docs already separate design-time publication from runtime activation, but slice planning does not. Without a dedicated slice, script patches and plugins are easy to mis-shape as "publish means active" or to blur design-time history with per-instance rollout state.

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Implementation Notes

The target-state contract is now sharper in the architecture docs:

- the scripting control-plane API now explicitly defines separate Game Design read surfaces for immutable script-patch and plugin publication status;
- runtime reads (`GetScriptPatchStatus`, `GetPluginStatus`) are now explicitly scoped to readiness and activation only, with a boundary rule that UIs and workflows must join design-time and runtime reads instead of inventing a fused state enum;
- Game Design service docs now explicitly require publication/readiness separation for ability-schema compatibility and plugin lifecycle visibility;
- the shared control-plane events doc now names `PluginVersionStatusChanged` as the canonical tenant-scoped design-time publication event family, separate from instance-scoped runtime activation events.

What remains open is the last implementation follow-through, not overall direction.

The first implementation cuts are now live for both artifact families: Game Design exposes `GetPublishedScriptPatchVersion` as the design-time publication read model for a script patch, and now also exposes a real upload-first plugin publication workflow through `UploadPluginBundle`, `PublishPluginVersion`, `GetPublishedPluginVersion`, and tenant-scoped `ListPluginVersionStatuses`. `UploadPluginBundle` now parses signed plugin ZIPs, enforces bounded intake limits, verifies allowlisted Ed25519 signatures, extracts immutable manifest metadata, persists the raw bundle in Game Design object storage, and records `SIGNATURE_VERIFIED` design-time state with deterministic `statusReason` support. `PublishPluginVersion` no longer trusts arbitrary caller metadata: it now requires a matching previously uploaded verified bundle, reuses that immutable metadata, verifies the requested `abilitySchemaDigest` against the published `AUTOMATION_SCRIPTING` participant digest for the target base version, fails closed on blocked component-policy decisions, exports plugin `assetRefs[]` into a plugin-version-scoped distribution manifest before the version becomes `PUBLISHED`, and marks older published versions for the same `pluginId` as `SUPERSEDED` with durable status reasons. Game Design now also exposes explicit design-time revoke mutation plus a durable append-only publication-event read model through `RevokePluginVersion` and `ListPluginVersionStatusEvents`, and publication lifecycle transitions now append `PluginVersionStatusChanged`-equivalent rows on upload verification, validation failure, publication, supersede, and design-time revoke. Automation's runtime activation path now consumes that live plugin publication read, current Game Session runtime-instance metadata, the immutable published release-bundle participant digests for the running version, the authoritative built-in command-alias registry surface in Game Session, and the currently pinned script-patch binding set in Automation to fail closed on non-`PUBLISHED` plugin versions, `baseVersionId` mismatches, `abilitySchemaDigest` mismatches, revoked signer metadata, blocked or missing component-policy decisions, unsupported built-in command-alias bindings, and instance-scoped binding conflicts before mutating runtime state. Report-only component-policy decisions now remain activatable and no longer get incorrectly fail-closed by later scheduled policy reconciliation.

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Scope

- script-only patch publish semantics around `baseVersionId`, `abilitySchemaDigest`, and tenant readiness visibility
- plugin publication semantics around signed-manifest metadata, publication status, and compatibility validation
- explicit read surfaces for publication/readiness versus per-instance rollout state
- UI/operator visibility expectations that distinguish published, ready, active, failed, and rolled-back states

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Out of Scope

- scripting runtime execution, timers, quotas, or trigger semantics
- plugin runtime execution architecture
- full creator UI implementation

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Locked Direction

- publication status is not the same thing as runtime activation.
- script patches may be published into design-time history before they are tenant-`READY` for execution.
- plugin publication validates and stores immutable design-time bundle metadata, but does not itself repin running instances.
- rollout and rollback remain explicit instance-scoped control-plane actions using already published artifacts.

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Current Remaining Work

- [x] No immediate follow-up remains inside this slice at the current boundary.
- [x] Keep later work in broader runtime scripting and plugin-activation families rather than reopening publication-boundary truth here.

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Recommended Stopping Points

1. Slice complete at the current publication-boundary seam. Resume later only if plugin runtime activation grows a new publication/readiness boundary instead of staying within scripting runtime families.

##### source-08-4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice-1-66: Checklist

- [x] Define target-state behavior and scope.
- [ ] Implement the slice end to end.
  - [x] `GetPublishedScriptPatchVersion` returns the script-patch publication read model from Game Design.
  - [x] `UploadPluginBundle`, `PublishPluginVersion`, `GetPublishedPluginVersion`, and `ListPluginVersionStatuses` expose a real upload-first Game Design plugin publication workflow without collapsing runtime activation state into the same API family.
  - [x] `UploadPluginBundle` now verifies signed bundle intake, persists raw bundle bytes in Game Design storage, extracts immutable manifest metadata, and records `SIGNATURE_VERIFIED` plus deterministic `statusReason` fields on design-time publication reads.
  - [x] `PublishPluginVersion` now fails closed unless the target `baseVersionId` has a published release attestation whose `AUTOMATION_SCRIPTING` participant digest exactly matches the uploaded plugin `abilitySchemaDigest`, and it exports plugin `assetRefs[]` into a plugin-version distribution manifest before writing `PUBLISHED`.
  - [x] Publishing a newer plugin version for the same `pluginId` now marks older published versions `SUPERSEDED` instead of leaving multiple activatable design-time rows.
  - [x] `RevokePluginVersion` now applies explicit design-time `REVOKED_DESIGN` transitions with durable `statusReason` support instead of leaving revoke-only behavior as architecture prose.
  - [x] Upload verification, validation failure, publication, supersede, and design-time revoke transitions now append durable `PluginVersionStatusChanged`-equivalent rows exposed through `ListPluginVersionStatusEvents`.
  - [x] `SetPluginActiveVersion` now fails closed on non-`PUBLISHED` plugin versions, `baseVersionId` / `abilitySchemaDigest` mismatches, revoked or policy-blocked plugin publication metadata, unsupported built-in command-alias bindings, and instance-scoped binding conflicts before mutating the Automation runtime registry.
  - [x] Report-only component-policy decisions no longer get treated as fail-closed by scheduled policy reconciliation or operator convergence reads.
- [x] Verify and close follow-ups.
<!-- /migration-source -->

### source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-10-24-26-49-51-62

#### World Design Mutation API Surface Vertical Slice - Version-scoped authoring and publication handoff (source lines 10, 24-26, 49-51, 62)

##### Preserved Source Text: source-08-5-task-list-world-design-mutation-api-surface-vertical-slice-10-24-26-49-51-62

<!-- migration-source path="design/project-management/vertical-slices/08.5-task-list-world-design-mutation-api-surface-vertical-slice.md" lines="10, 24-26, 49-51, 62" sha256="d627442cf36deef89c16392c6b86e7bd754f78b2fc380c7a3cbf603a3260f110" heading-offset="3" -->
- Game Design `SaveRevision` is now version-scoped and carries typed `worldDesignMutation` payloads through to World Management, including the scoped `WORLD_GENERATION_SUBTREE` shape; the caller path saves revision history and applies the concrete change to World Management through the canonical API instead of treating opaque revision JSON as the authoritative world graph.
<!-- source-gap: lines 11-23 -->
The architecture already assigns authoring history and publish orchestration to Game Design while keeping authoritative Draft world template rows in World Management. That split is correct, but the current implementation surface is still too thin for the target model: Game Design can save generic revision payloads, and World Management exposes narrow operational/template endpoints, but there is no canonical API set that applies concrete world revisions with shared idempotency, expected epochs, reference validation, and publish-digest convergence.

Without this slice, different editor paths can make incompatible choices about whether a failed write is retryable, a true Draft conflict, an unresolved entity/script reference, or an invalid version-state mutation. Publish reconciliation then cannot safely replay commits in one deterministic order.
<!-- source-gap: lines 27-48 -->
- Game Design owns revision history, commits, version state, and publish orchestration; World Management owns the authoritative Draft and Published world template rows.
- World Management design-time writes are allowed only for Draft versions and must reject Published, Active, Failed, or Retired version scopes.
- Game Design applies concrete revisions to World Management through typed design APIs rather than relying on opaque JSON blobs as the authoritative world graph.
<!-- source-gap: lines 52-61 -->
- Game Design has a first caller path that saves a revision and applies it to World Management through the canonical API rather than directly mutating World tables or treating opaque revision JSON as the authoritative graph.
<!-- /migration-source -->
